#!/usr/bin/env python3
"""Replay independent effects and require every local completion criterion before marking done."""
import argparse
import json
import subprocess
import time
from pathlib import Path
from evidence_paths import validate_evidence_path
from checker import check

p=argparse.ArgumentParser();p.add_argument('--evidence',required=True);a=p.parse_args();root=Path(a.evidence).resolve()
repo=Path(__file__).resolve().parents[1]
validate_evidence_path(root)
items=json.loads((root/'index.json').read_text());errors=[];replayed=[];requirements={}
def require(name,mode,scenario,count=1,**settings):
    matches=[x for x in items if x['settings'].get('mode')==mode and x['settings'].get('scenario')==scenario and x['metrics']['checker']['passed'] and all(x['settings'].get(k)==v for k,v in settings.items())]
    requirements[name]=[x['run'] for x in matches]
    if len(matches)<count:errors.append(f'{name}: expected {count}, got {len(matches)}')
    return matches
for item in items:
    d=root/item['run']
    result=check(json.loads((d/'expected.json').read_text()),json.loads((d/'remote.json').read_text()),json.loads((d/'db-effects.json').read_text()),json.loads((d/'db-orders.json').read_text()))
    replayed.append(dict(run=d.name,**result))
    if not result['passed']:errors.append(dict(run=d.name,errors=result['errors']))
    if item['settings'].get('scenario')=='end-to-end':
        source=json.loads((d/'source-final.json').read_text())
        wanted={x['orderId'] for x in json.loads((d/'source-input.json').read_text())}
        if len(source['history'])!=len(wanted) or {x['order_id'] for x in source['history']}!=wanted or {x['order_id'] for x in source['orders']}!=wanted:
            errors.append(f'{d.name}: source inventory history does not cover each HTTP order exactly once')
for mode in ['sequential','async','inbox']:
    api=require('api-'+mode,mode,'api',3,observe=True)
    for observed in [True,False]:require(f'clean-{mode}-{observed}',mode,'clean',3,observe=observed)
    for scenario in ['db','response-loss','redelivery','retry','hotkey','broker-kill']:require(scenario+'-'+mode,mode,scenario)
    for fault in [False,True]:require(f'http-{mode}-{fault}',mode,'end-to-end',boundary='none',concurrentDuplicate=False,sourcePollMs=100,sellerFault=fault)
    for item in api[-3:]:
        if mode=='inbox' and not item['metrics']['measuredNormalSloPassed']:errors.append(f'{item["run"]}: inbox normal SLO failed')
        d=root/item['run']
        r=subprocess.run(['python3',str(repo/'experiments/verify_budgets.py'),str(d)],capture_output=True,text=True)
        if r.returncode:errors.append(dict(run=d.name,budget=r.stdout+r.stderr))
        if mode in ['async','inbox'] and r.returncode==0 and not json.loads(r.stdout)['differentOrdersOverlapped']:errors.append(f'{d.name}: no independent evidence of different-order parallelism')
    if mode in ['sequential','async']:
        for scenario in ['ack-kill','ack-release','external-kill','business-before-kill','rebalance']:require(scenario+'-'+mode,mode,scenario)
for scenario in ['inbox-kill','worker-kill','external-kill','business-before-kill','inbox-before-kill','backlog']:require(scenario+'-inbox','inbox',scenario)
for poll in [1000,100]:require('poll-'+str(poll),'inbox','end-to-end',3,boundary='none',concurrentDuplicate=False,sourcePollMs=poll,sellerFault=False)
for gate in ['broker_ack','inventory_broker_ack']:
    cases=require(gate,'inbox','end-to-end',2,boundary=gate)
    for item in cases:
        d=root/item['run'];event=(d/'hooks'/f'{gate}.reached').read_text().splitlines()[0]
        rows=json.loads((d/('at-boundary-order-outbox.json' if gate=='broker_ack' else 'at-boundary-inventory-outbox.json')).read_text())
        if not any(x['event_id']==event and x['status']=='PENDING' for x in rows):errors.append(f'{d.name}: no PENDING Outbox at broker ack')
        if event not in (d/'at-boundary-broker-records.jsonl').read_text():errors.append(f'{d.name}: no real broker record')
        if item['metrics']['checker'].get('sourceStock')!=988:errors.append(f'{d.name}: source stock mismatch')
require('inventory-concurrent-duplicate','inbox','end-to-end',concurrentDuplicate=True)
admitted=require('global-admission','inbox','global-admission')
for item in admitted:
    checked=item['metrics']['checker']
    if checked.get('acceptedUnfinished')!=200 or not checked.get('rejected201st'):errors.append('Global admission missing 200/201 proof')
for mode in ['sequential','async','inbox']:
    for scenario,hypothesis in [('api','seller_api_delay'),('db','shared_db_pool')]+([('ack-release','post_business_ack_delay')] if mode!='inbox' else []):
        cases=[x for x in items if x['settings'].get('mode')==mode and x['settings'].get('scenario')==scenario]
        if not cases or cases[-1]['diagnosis']['verdict'][hypothesis]!='수용':errors.append(f'Diagnostic proof missing: {mode}/{hypothesis}')
if not (root/'baseline'/'actual-run.json').exists():errors.append('Original baseline missing')
result=dict(passed=not errors,completedAt=int(time.time()*1000),replayedRuns=len(replayed),effectCount=sum(x['expected'] for x in replayed),requirements=requirements,errors=errors)
(root/'checker-replay.json').write_text(json.dumps(replayed,ensure_ascii=False,indent=2))
(root/'local-completion.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
print(json.dumps({k:v for k,v in result.items() if k!='requirements'},ensure_ascii=False))
raise SystemExit(bool(errors))
