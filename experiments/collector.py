#!/usr/bin/env python3
"""Lives outside the failed JVM; retains bounded pre-incident and post-incident observations."""
import argparse
import collections
import json
import os
import resource
import subprocess
import time
import urllib.request
from pathlib import Path
from evidence_paths import validate_evidence_path
from docker_metrics import DockerMetrics, collector_peak_rss_bytes

def get(url):
    with urllib.request.urlopen(url, timeout=3) as response:
        return json.load(response)

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--directory', required=True)
    p.add_argument('--pids',default='')
    p.add_argument('--interval', type=float, default=0.25)
    args = p.parse_args()
    root = Path(args.directory).resolve()
    repository = Path(__file__).resolve().parents[1]
    validate_evidence_path(root)
    ring = collections.deque(maxlen=120)  # 30s at 250ms; sampled values, raw traces separately retained.
    detected = None
    end_capture = None
    samples = 0
    lost = 0
    late_samples = 0
    missed_schedule_slots = 0
    io_bytes = 0
    start = time.monotonic()
    try:
        infra = DockerMetrics()
        infra_error = None
    except Exception as error:
        infra = None
        infra_error = str(error)
    with (root/'observations.jsonl').open('w') as out:
        while not (root/'collector.stop').exists():
            begin = time.monotonic()
            item = dict(time=int(time.time()*1000))
            try:
                item['service'] = get('http://localhost:8090/observe')
            except Exception as e:
                item['service'] = None
                item['serviceError'] = str(e)
            try:
                snapshot = get(os.environ.get('PARTNER_API_URL', 'http://localhost:8099') + '/snapshot')
                item['externalEffects'] = len(snapshot['effects'])
                item['externalActive'] = snapshot['active']
                expected = json.loads((root/'expected.json').read_text()) if (root/'expected.json').exists() else []
                done = {x['event_id'] for x in snapshot['effects']}
                missing = [x for x in expected if x['eventId'] not in done]
                item['unfinished'] = len(missing)
                ages = {}
                for e in missing:
                    ages[e['sellerId']] = max(ages.get(e['sellerId'],0),item['time']-e['occurredAt'])
                item['oldestMs'] = ages
            except Exception as e:
                item['externalError'] = str(e)
            if samples % 4 == 0:
                if infra:item['infra'] = infra.sample()
                elif infra_error:item['infraError'] = infra_error
                item['collectorPeakRssBytes'] = collector_peak_rss_bytes()
                try:
                    repository=Path(__file__).resolve().parents[1]
                    query="SELECT (SELECT COUNT(*) FROM partner_effect) AS business_done,(SELECT COUNT(*) FROM inbox WHERE state<>'DONE') AS inbox_pending,(SELECT COUNT(*) FROM inbox) AS inbox_retained;"
                    raw=subprocess.check_output(['docker','compose','-p','partner-isolation','-f',str(repository/'docker-compose.experiment.yml'),'exec','-T','mysql','mysql','-uroot','-plabpassword','--batch','partner_db','-e',query],text=True,stderr=subprocess.DEVNULL,timeout=3)
                    lines=raw.splitlines()
                    item['independentDb']={k:int(v) for k,v in zip(lines[0].split('\t'),lines[1].split('\t'))}
                except Exception as e:item['independentDbError']=str(e)
            pids=args.pids
            if (root/'process-pids.json').exists():pids=','.join(str(x['pid']) for x in json.loads((root/'process-pids.json').read_text()))
            if pids:
                try:
                    rss=subprocess.check_output(['ps','-o','rss=','-p',pids],text=True)
                    item['processRssKiB']=sum(int(x) for x in rss.split())
                except Exception as e:item['rssError']=str(e)
            item['sampleMs'] = (time.monotonic()-begin)*1000
            encoded = json.dumps(item) + '\n'
            if io_bytes + len(encoded.encode()) > 8*1024*1024:
                lost += 1
            else:
                out.write(encoded)
                out.flush()
                io_bytes += len(encoded.encode())
            ring.append(item)
            while ring and item['time']-ring[0]['time']>30000:ring.popleft()
            if item['sampleMs']>args.interval*1000:
                late_samples+=1
                missed_schedule_slots+=max(0,int(item['sampleMs']/(args.interval*1000))-1)
            samples += 1
            service = item.get('service') or {}
            anomaly = max(item.get('oldestMs',{}).values(),default=0) >= 2000 or service.get('dbWaiting',0)>0
            if detected is None and anomaly:
                detected = int(time.time()*1000)
                incident = root/'incident-before.json'
                incident.write_text(json.dumps(list(ring),indent=2))
                with incident.open('rb') as f:
                    os.fsync(f.fileno())
                (root/'detection.json').write_text(json.dumps(dict(detectedAt=detected, evidenceSavedAt=int(time.time()*1000), oldestMs=item.get('oldestMs'), dbWaiting=service.get('dbWaiting'))))
                end_capture = detected + 5000
            if detected and item['time'] <= end_capture:
                with (root/'incident-after.jsonl').open('a') as incident:
                    incident.write(encoded)
            time.sleep(max(0,args.interval-(time.monotonic()-begin)))
    usage = resource.getrusage(resource.RUSAGE_SELF)
    children = resource.getrusage(resource.RUSAGE_CHILDREN)
    (root/'collector-cost.json').write_text(json.dumps(dict(samples=samples,droppedSamples=lost,lateSamples=late_samples,missedScheduleSlots=missed_schedule_slots,bytes=io_bytes,
        cpuSeconds=usage.ru_utime+usage.ru_stime,childCpuSeconds=children.ru_utime+children.ru_stime,maxRssNative=usage.ru_maxrss,maxRssUnit='bytes' if __import__('sys').platform=='darwin' else 'KiB',elapsedSeconds=time.monotonic()-start,
        intervalSeconds=args.interval,preWindowSeconds=30,postWindowSeconds=5),indent=2))
