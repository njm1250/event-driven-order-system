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

def get(url):
    with urllib.request.urlopen(url, timeout=3) as response:
        return json.load(response)

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--directory', required=True)
    p.add_argument('--pids',default='')
    p.add_argument('--interval', type=float, default=0.25)
    args = p.parse_args()
    root = Path(args.directory)
    ring = collections.deque(maxlen=120)  # 30s at 250ms; sampled values, raw traces separately retained.
    detected = None
    end_capture = None
    samples = 0
    lost = 0
    io_bytes = 0
    start = time.monotonic()
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
                snapshot = get('http://localhost:8099/snapshot')
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
                try:
                    repository=Path(__file__).resolve().parents[1]
                    query="SELECT (SELECT COUNT(*) FROM partner_effect) AS business_done,(SELECT COUNT(*) FROM inbox WHERE state<>'DONE') AS inbox_pending,(SELECT COUNT(*) FROM inbox) AS inbox_retained;"
                    raw=subprocess.check_output(['docker','compose','-p','partner-isolation','-f',str(repository/'docker-compose.experiment.yml'),'exec','-T','mysql','mysql','-uroot','-plabpassword','--batch','partner_db','-e',query],text=True,stderr=subprocess.DEVNULL,timeout=3)
                    lines=raw.splitlines()
                    item['independentDb']={k:int(v) for k,v in zip(lines[0].split('\t'),lines[1].split('\t'))}
                except Exception as e:item['independentDbError']=str(e)
            if args.pids:
                try:
                    rss=subprocess.check_output(['ps','-o','rss=','-p',args.pids],text=True)
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
            samples += 1
            service = item.get('service') or {}
            anomaly = max(item.get('oldestMs',{}).values(),default=0) >= 2000 or service.get('dbWaiting',0)>0
            if detected is None and anomaly:
                detected = item['time']
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
    (root/'collector-cost.json').write_text(json.dumps(dict(samples=samples,droppedSamples=lost,bytes=io_bytes,
        cpuSeconds=usage.ru_utime+usage.ru_stime,maxRssNative=usage.ru_maxrss,elapsedSeconds=time.monotonic()-start,
        intervalSeconds=args.interval,preWindowSeconds=30,postWindowSeconds=5),indent=2))
