"""Where each component runs. Defaults are the local Docker Compose stack; the AWS cluster sets
environment variables so the same controller drives a partner JVM, MySQL and Kafka on other hosts."""
import os
import subprocess

SERVICE_URL = os.environ.get('PARTNER_SERVICE_URL', 'http://localhost:8090')
MYSQL_HOST = os.environ.get('MYSQL_HOST')                    # None: MySQL in the local compose stack
PARTNER_HOST = os.environ.get('PARTNER_HOST')                # None: partner JVM is a local process
KAFKA_BOOTSTRAP = os.environ.get('KAFKA_BOOTSTRAP', 'localhost:39092')
KAFKA_HOSTS = [h for h in os.environ.get('KAFKA_HOSTS', '').split(',') if h]
SSH_KEY = os.environ.get('SSH_KEY')
REPLICATION_FACTOR = int(os.environ.get('KAFKA_REPLICATION_FACTOR', '1'))
# name=ip pairs whose /proc/stat is sampled around each workload, e.g. "mysql=10.0.0.5,kafka-1=..."
CPU_HOSTS = dict(x.split('=', 1) for x in os.environ.get('CPU_HOSTS', '').split(',') if x)
DISTRIBUTED = bool(MYSQL_HOST or PARTNER_HOST)


def ssh_args(host, tty=False):
    args = ['ssh', '-o', 'StrictHostKeyChecking=no', '-o', 'UserKnownHostsFile=/dev/null', '-o', 'LogLevel=ERROR']
    if SSH_KEY: args += ['-i', SSH_KEY]
    if tty: args += ['-tt']
    return args + [f'ec2-user@{host}']


def ssh(host, command, timeout=30):
    return subprocess.check_output(ssh_args(host) + [command], text=True, timeout=timeout)


def mysql_command(database, query):
    return ['mysql', '-h', MYSQL_HOST, '-uroot', '-plabpassword', '--batch', '--raw', database, '-e', query]


# One ssh round trip per host: cpu line of /proc/stat and the busiest whole-disk line of /proc/diskstats.
HOST_SAMPLE = "head -1 /proc/stat; awk '$3 ~ /^(nvme[0-9]+n[0-9]+|xvd[a-z]+)$/' /proc/diskstats"


def host_sample(host):
    lines = ssh(host, HOST_SAMPLE).splitlines()
    cpu = [int(x) for x in lines[0].split()[1:]]
    disks = [line.split() for line in lines[1:]]
    # diskstats split fields: [7] writes completed, [9] sectors written, [10] ms spent writing
    disk = max(disks, key=lambda d: int(d[7])) if disks else None
    return dict(busy=sum(cpu) - cpu[3] - cpu[4], total=sum(cpu), steal=cpu[7] if len(cpu) > 7 else 0,
                diskWrites=int(disk[7]) if disk else None, diskWriteMs=int(disk[10]) if disk else None,
                diskSectorsWritten=int(disk[9]) if disk else None)


def sample_hosts():
    result = {}
    for name, host in CPU_HOSTS.items():
        try: result[name] = host_sample(host)
        except Exception as error: result[name] = repr(error)
    return result


def host_usage(before, after, seconds):
    """Per host: busy and steal share of CPU, disk writes per second and mean write latency.
    A failed sample stays None instead of becoming 0."""
    result = {}
    for name, a in after.items():
        b = before.get(name)
        if not (isinstance(a, dict) and isinstance(b, dict) and a['total'] > b['total']):
            result[name] = None
            continue
        total = a['total'] - b['total']
        usage = dict(cpuBusy=round((a['busy'] - b['busy']) / total, 4), cpuSteal=round((a['steal'] - b['steal']) / total, 4))
        if a['diskWrites'] is not None and b['diskWrites'] is not None:
            writes = a['diskWrites'] - b['diskWrites']
            usage['diskWritesPerSecond'] = round(writes / seconds, 1) if seconds else None
            usage['diskWriteLatencyMs'] = round((a['diskWriteMs'] - b['diskWriteMs']) / writes, 3) if writes else None
            usage['diskWriteMBPerSecond'] = round((a['diskSectorsWritten'] - b['diskSectorsWritten']) * 512 / 1048576 / seconds, 2) if seconds else None
        result[name] = usage
    return result
