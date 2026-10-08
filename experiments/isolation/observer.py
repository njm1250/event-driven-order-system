#!/usr/bin/env python3
"""Samples the running services once per second and judges the delivery alert.

Alert rule: ALERT when two valid samples in a row show an overdue obligation; UNKNOWN when the
source's backlog snapshot is older than 3s (its refresh stopped), so a stale value is never read as
healthy, and it stays UNKNOWN for a first overdue sample after that until the next one confirms;
NORMAL otherwise. While the pause file exists nothing is sampled (observer-off windows).
"""
import argparse
import json
import time
import urllib.request
from pathlib import Path

STALE_MS = 3000
PARTNER_KEYS = ['instance', 'mode', 'committedRemaining', 'retryRemaining', 'active', 'sellerActive', 'runningClaims',
                'pcWorkRemaining', 'inboxOldestPendingMsBySeller', 'inboxPendingBySeller', 'circuits', 'heapUsed',
                'gcMillis', 'cpuNanos', 'dbWaiting', 'assigned', 'brokerError']


def get(url, timeout=0.8):
    with urllib.request.urlopen(url, timeout=timeout) as response:
        return json.load(response)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--out', required=True)
    p.add_argument('--source')
    p.add_argument('--partners', default='')
    p.add_argument('--stop-file', required=True)
    p.add_argument('--pause-file', required=True)
    a = p.parse_args()
    partners = [x for x in a.partners.split(',') if x]
    previous_overdue = False
    previous_state = None
    tick = time.time()
    with open(a.out, 'a') as out:
        while not Path(a.stop_file).exists():
            tick += 1
            if Path(a.pause_file).exists():
                time.sleep(max(0, tick - time.time()))
                continue
            began = time.time()
            sample = dict(time=int(began * 1000))
            if a.source:
                try:
                    source = get(a.source + '/experiment/observe')
                    sample['source'] = source
                    valid = sample['time'] - source['snapshotAt'] <= STALE_MS
                except Exception as error:
                    sample['sourceError'] = repr(error)
                    valid = False
                if not valid:
                    sample['alert'] = 'UNKNOWN'
                    previous_overdue = False
                else:
                    overdue = sum(v['overdue'] for v in source['sellers'].values()) > 0
                    if overdue and previous_overdue:
                        sample['alert'] = 'ALERT'
                    elif overdue and previous_state == 'UNKNOWN':
                        sample['alert'] = 'UNKNOWN'
                    else:
                        sample['alert'] = 'NORMAL'
                    previous_overdue = overdue
                previous_state = sample['alert']
            sample['partners'] = []
            for url in partners:
                try:
                    data = get(url + '/observe', timeout=2)
                    sample['partners'].append({k: data.get(k) for k in PARTNER_KEYS})
                except Exception as error:
                    sample['partners'].append(dict(url=url, error=repr(error)))
            lags = [x.get('committedRemaining') for x in sample['partners'] if x.get('committedRemaining') is not None]
            sample['lag'] = max(lags) if lags else None
            sample['sampleMs'] = int((time.time() - began) * 1000)
            out.write(json.dumps(sample, separators=(',', ':')) + '\n')
            out.flush()
            time.sleep(max(0, tick - time.time()))


if __name__ == '__main__':
    main()
