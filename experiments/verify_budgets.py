#!/usr/bin/env python3
"""Verify overlap and retry admission from independent external API request history."""
import argparse
import collections
import json
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('directory');a=p.parse_args();root=Path(a.directory)
root=root.resolve();repo=Path(__file__).resolve().parents[1]
if root==repo or repo in root.parents:raise SystemExit('Evidence must be external')
remote=json.loads((root/'remote.json').read_text());manifest=json.loads((root/'manifest.json').read_text())
errors=[];overlap={};parallel_orders=False;same_order_overlaps=0
for seller in {'normal','slow'}:
    requests=[x for x in remote['attempts'] if x['seller_id']==seller]
    changes=[]
    for row in requests:
        changes.extend([(row['start_at'],1,row['order_id']),(row['end_at'] or row['start_at'],-1,row['order_id'])])
    active=0;peak=0
    for timestamp,delta,order in sorted(changes,key=lambda x:(x[0],x[1])):
        active+=delta;peak=max(peak,active)
    overlap[seller]=peak
    if peak>2:errors.append(f'{seller} concurrent calls {peak} exceeds 2')
    for first in requests:
        for second in requests:
            if first['order_id']!=second['order_id'] and first['start_at']<second['start_at']<(first['end_at'] or 0):parallel_orders=True
            if first['order_id']==second['order_id'] and first['event_id']!=second['event_id'] and first['start_at']<second['start_at']<(first['end_at'] or 0):same_order_overlaps+=1
if same_order_overlaps:errors.append(f'{same_order_overlaps} overlapping operations of the same order')
traces=json.loads((root/'traces.json').read_text())
# Admission is authoritative for the rolling retry budget; network requests can start later.
starts=collections.defaultdict(list)
for t in traces:
    if t['stage']=='retry_admitted':starts[t['sellerId']].append(t['admittedAt'])
for seller,values in starts.items():
    values.sort()
    for value in values:
        count=sum(value-1000<x<=value for x in values)
        if count>2:errors.append(f'{seller} retry admissions {count}/1000ms')
if manifest['settings'].get('mode') in {'async','inbox'} and manifest['settings'].get('scenario') in {'retry','hotkey'} and not starts:
    errors.append('Retry admission evidence missing')
result=dict(passed=not errors,errors=errors,peakExternalCalls=overlap,differentOrdersOverlapped=parallel_orders,sameOrderOverlaps=same_order_overlaps,
            retryAdmissions={s:len(v) for s,v in starts.items()},retryWindowMs=1000,retryBudget=2,
            budgetTraceAvailable=bool(starts))
(root/'budget-checker.json').write_text(json.dumps(result,indent=2))
print(json.dumps(result))
raise SystemExit(not result['passed'])
