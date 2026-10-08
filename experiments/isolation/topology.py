"""Where each component runs and how the controller reaches it.

AWS: one host per component, addresses from controller.env, commands over ssh, default ports.
Local (ISOLATION_LOCAL=1): every component is a process on this machine with its own port and work
directory, Kafka and MySQL come from docker-compose.experiment.yml, commands run in a local shell.
The runner uses only this interface, so the same run plan works in both places.
"""
import os
import subprocess
from dataclasses import dataclass
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
LOCAL = os.environ.get('ISOLATION_LOCAL') == '1'
SSH_KEY = os.environ.get('SSH_KEY')
LOCAL_ROOT = Path(os.environ.get('ISOLATION_LOCAL_ROOT', REPO/'experiment-evidence'/'local-hosts'))


@dataclass(frozen=True)
class Host:
    name: str
    address: str
    port: int = 0

    @property
    def workdir(self):
        return str(LOCAL_ROOT/self.name) if LOCAL else '/home/ec2-user'

    @property
    def repo(self):
        return str(REPO) if LOCAL else '/home/ec2-user/repo'

    def url(self, port=None):
        return f'http://{self.address}:{port or self.port}'


def _split(name):
    return [x for x in os.environ.get(name, '').split(',') if x]


if LOCAL:
    KAFKA_BOOTSTRAP = 'localhost:39092'
    KAFKA_HOSTS = [Host('kafka-1', 'localhost')]
    REPLICATION_FACTOR = 1
    MIN_ISR = 1
    PARTNERS = [Host('partner-1', 'localhost', 8090), Host('partner-2', 'localhost', 8091)]
    PARTNER_DB = Host('partner-db', '127.0.0.1', 13306)
    SOURCE = Host('source', 'localhost', 8081)
    SOURCE_DB = Host('source-db', '127.0.0.1', 13306)
    MOCK = Host('mock', 'localhost', 8099)
    PRODUCER = Host('producer', 'localhost')
    CLOCK_HOSTS = []
else:
    KAFKA_BOOTSTRAP = os.environ.get('KAFKA_BOOTSTRAP', '')
    KAFKA_HOSTS = [Host(f'kafka-{i + 1}', a) for i, a in enumerate(_split('KAFKA_HOSTS'))]
    REPLICATION_FACTOR = int(os.environ.get('KAFKA_REPLICATION_FACTOR', '3'))
    MIN_ISR = int(os.environ.get('KAFKA_MIN_ISR', '2'))
    PARTNERS = [Host(f'partner-{i + 1}', a, 8090) for i, a in enumerate(_split('PARTNER_HOSTS'))]
    PARTNER_DB = Host('partner-db', os.environ.get('PARTNER_DB_HOST', ''), 3306)
    SOURCE = Host('source', os.environ.get('SOURCE_HOST', ''), 8081)
    SOURCE_DB = Host('source-db', os.environ.get('SOURCE_DB_HOST', ''), 3306)
    MOCK = Host('mock', os.environ.get('MOCK_HOST', ''), 8099)
    PRODUCER = Host('producer', os.environ.get('PRODUCER_HOST', ''))
    CLOCK_HOSTS = KAFKA_HOSTS + PARTNERS + [PARTNER_DB, SOURCE, SOURCE_DB, MOCK, PRODUCER]


def ssh_args(host):
    args = ['ssh', '-n', '-o', 'StrictHostKeyChecking=no', '-o', 'UserKnownHostsFile=/dev/null', '-o', 'LogLevel=ERROR',
            '-o', 'ConnectTimeout=10']
    if SSH_KEY:
        args += ['-i', SSH_KEY]
    return args + [f'ec2-user@{host.address}']


def run(host, command, timeout=60, check=True):
    """Runs a shell command on the host and returns its output."""
    if LOCAL:
        Path(host.workdir).mkdir(parents=True, exist_ok=True)
        args = ['bash', '-c', f'cd {host.workdir} && {command}']
    else:
        args = ssh_args(host) + [command]
    result = subprocess.run(args, capture_output=True, text=True, timeout=timeout)
    if check and result.returncode != 0:
        raise RuntimeError(f'{host.name}: {command[:120]} -> {result.returncode} {result.stderr[-400:]}')
    return result.stdout


def copy_to(host, local_path, remote_path):
    if LOCAL:
        target = Path(host.workdir)/remote_path
        target.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(['cp', str(local_path), str(target)], check=True)
        return
    args = ['scp', '-q', '-o', 'StrictHostKeyChecking=no', '-o', 'UserKnownHostsFile=/dev/null', '-o', 'LogLevel=ERROR']
    if SSH_KEY:
        args += ['-i', SSH_KEY]
    subprocess.run(args + [str(local_path), f'ec2-user@{host.address}:{remote_path}'], check=True, timeout=300)


def copy_from(host, remote_path, local_path):
    if LOCAL:
        source = Path(host.workdir)/remote_path
        if source.exists():
            subprocess.run(['cp', str(source), str(local_path)], check=True)
        return
    args = ['scp', '-q', '-o', 'StrictHostKeyChecking=no', '-o', 'UserKnownHostsFile=/dev/null', '-o', 'LogLevel=ERROR']
    if SSH_KEY:
        args += ['-i', SSH_KEY]
    subprocess.run(args + [f'ec2-user@{host.address}:{remote_path}', str(local_path)], check=False, timeout=600)


def mysql(db_host, database, query, timeout=60):
    """Rows as dicts (tab separated client output); None for NULL."""
    if LOCAL:
        args = ['docker', 'exec', '-i', 'partner-isolation-mysql-1', 'mysql', '-uroot', '-plabpassword', '--batch', '--raw', database, '-e', query]
    else:
        args = ['mysql', '-h', db_host.address, '-uroot', '-plabpassword', '--batch', '--raw', database, '-e', query]
    out = subprocess.run(args, capture_output=True, text=True, timeout=timeout, check=True).stdout.splitlines()
    if not out:
        return []
    header = out[0].split('\t')
    return [{k: (None if v == 'NULL' else v) for k, v in zip(header, line.split('\t'))} for line in out[1:]]


def kafka_topics(command):
    if LOCAL:
        args = ['docker', 'exec', 'partner-isolation-kafka-1', '/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server', 'localhost:9092'] + command
        return subprocess.run(args, capture_output=True, text=True, timeout=60, check=True).stdout
    quoted = ' '.join(f"'{x}'" for x in command)
    return run(KAFKA_HOSTS[0], f'docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 {quoted}')
