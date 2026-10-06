"""Read only this experiment's container counters through the local Docker socket."""
import http.client
import json
import resource
import socket
import subprocess
import sys
from pathlib import Path

class UnixConnection(http.client.HTTPConnection):
    def __init__(self, path):
        super().__init__('localhost', timeout=1)
        self.path = path
    def connect(self):
        self.sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.sock.settimeout(self.timeout)
        self.sock.connect(self.path)

class DockerMetrics:
    def __init__(self):
        endpoint = subprocess.check_output(['docker','context','inspect','--format','{{.Endpoints.docker.Host}}'],text=True).strip()
        if not endpoint.startswith('unix://'):raise ValueError('Local Unix Docker endpoint required')
        self.path = endpoint[7:]
        repository = Path(__file__).resolve().parents[1]
        self.containers = subprocess.check_output(['docker','compose','-p','partner-isolation','-f',str(repository/'docker-compose.experiment.yml'),'ps','-q'],text=True).split()
        self.names = {}
        for container in self.containers:
            info = self.get('/containers/'+container+'/json')
            self.names[container] = info['Config']['Labels']['com.docker.compose.service']
    def get(self, url):
        connection = UnixConnection(self.path)
        try:
            connection.request('GET','/v1.45'+url)
            response = connection.getresponse()
            if response.status != 200:raise RuntimeError('Docker status '+str(response.status))
            return json.loads(response.read())
        finally:connection.close()
    def sample(self):
        rows = {}
        for container in self.containers:
            try:
                stats = self.get('/containers/'+container+'/stats?stream=false&one-shot=true')
                memory = stats.get('memory_stats',{})
                usage = memory.get('usage')
                cache = memory.get('stats',{}).get('inactive_file',memory.get('stats',{}).get('total_inactive_file',0))
                rows[self.names[container]] = dict(memoryUsageBytes=usage,memoryWithoutInactiveFileBytes=max(0,usage-cache) if usage is not None else None,cpuTotalNanos=stats.get('cpu_stats',{}).get('cpu_usage',{}).get('total_usage'))
            except Exception as error:rows[self.names[container]] = dict(error=str(error))
        return rows

def collector_peak_rss_bytes():
    value = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return value if sys.platform=='darwin' else value*1024
