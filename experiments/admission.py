#!/usr/bin/env python3
"""Real source API admission cap: 200 accepted unfinished orders, 201st rejected, then drain."""
import argparse
import json
import sys
import time
import urllib.error
from pathlib import Path
from run import REPO,COMPOSE,command,sql,http,wait_for,java,launch,stop,new_root,metadata,save,snapshot,report,now,PROCESSES,HANDLES
from checker import check
from end_to_end import reset_source
p=argparse.ArgumentParser();p.add_argument('--evidence',required=True);a=p.parse_args();base=Path(a.evidence).resolve()
if base==REPO or REPO in base.parents:raise SystemExit('Evidence must be external')
reset_source();root=new_root(base,'e2e-inbox-admission');metadata(root,dict(mode='inbox',scenario='global-admission'))
processes=[];collector=None;controller=[]
def record(action,**detail):
    controller.append(dict(time=now(),action=action,**detail));save(root/'controller.json',controller)
try:
    processes.append(launch([sys.executable,str(REPO/'experiments/mock-partner-api/server.py'),'--database',str(root/'external.sqlite')],root/'mock.log'))
    wait_for(lambda:http('http://localhost:8099/health'))
    partner=java('partner-integration-service',root,dict(PARTNER_MODE='inbox',PARTNER_GROUP='admission-'+root.name));processes.append(partner)
    wait_for(lambda:http('http://localhost:8090/observe').get('assigned',0)>0)
    command(COMPOSE+['exec','-T','mysql','mysql','-uroot','-plabpassword'],input=(REPO/'experiments/migrations/001-source-schema.sql').read_text())
    processes.append(java('order-service',root));processes.append(java('inventory-service',root))
    def ready():
        try:return http('http://localhost:8081/orders/999999')
        except urllib.error.HTTPError as e:return e.code==404
    wait_for(ready);wait_for(lambda:sql('SHOW TABLES','inventory_db'))
    sql("INSERT INTO inventories(product_cd,stock_quantity,version) VALUES('SKU-1',1000,0)",'inventory_db')
    http('http://localhost:8099/control',dict(sellerId='slow',failureCount=100000))
    record('fault_injected',kind='seller_error',failureCount=100000)
    collector=launch([sys.executable,str(REPO/'experiments/collector.py'),'--directory',str(root),'--pids',','.join(str(x.pid) for x in processes)],root/'collector.log')
    orders=[http('http://localhost:8081/orders',dict(productCode='SKU-1',quantity=2,price=100,sellerId='slow',runId=root.name)) for _ in range(200)]
    save(root/'source-input.json',orders)
    try:
        http('http://localhost:8081/orders',dict(productCode='SKU-1',quantity=2,price=100,sellerId='normal',runId=root.name))
        raise AssertionError('201st unfinished order was admitted')
    except urllib.error.HTTPError as e:
        if e.code!=409:raise
        record('admission_rejected',status=e.code,body=e.read().decode())
    count=sql("SELECT (SELECT COUNT(*) FROM orders WHERE order_status='PENDING')+(SELECT COUNT(*) FROM outbox_event o WHERE topic='partner-order-requests' AND NOT EXISTS(SELECT 1 FROM partner_db.partner_effect e WHERE e.event_id=o.event_id)) unfinished",'order_db')[0]['unfinished']
    if int(count)!=200:raise AssertionError(f'Expected exact unfinished 200, got {count}')
    save(root/'at-cap-observe.json',http('http://localhost:8090/observe'))
    wait_for(lambda:len(sql("SELECT event_id FROM outbox_event WHERE topic='partner-order-requests'",'order_db'))==200,timeout=45)
    expected=[json.loads(x['payload']) for x in sql("SELECT payload FROM outbox_event WHERE topic='partner-order-requests' ORDER BY id",'order_db')]
    save(root/'expected.json',expected)
    first=orders[0]['orderId']
    before=http(f'http://localhost:8081/orders/{first}')
    for url,data,method in [(f'http://localhost:8081/orders/{first}',dict(quantity=3,price=120),'PATCH'),(f'http://localhost:8081/orders/{first}/cancel',{},'POST')]:
        try:
            http(url,data,method=method)
            raise AssertionError('Mutation bypassed the full global budget')
        except urllib.error.HTTPError as e:
            if e.code!=409:raise
            record('mutation_admission_rejected',method=method,status=e.code)
        if http(f'http://localhost:8081/orders/{first}')!=before:raise AssertionError('Rejected mutation changed source state')
    save(root/'rejected-mutation-source.json',before)
    http('http://localhost:8099/control',dict(sellerId='slow',failureCount=0));record('fault_removed')
    wait_for(lambda:len(sql('SELECT event_id FROM partner_effect'))==200,timeout=140)
    readmitted=http('http://localhost:8081/orders',dict(productCode='SKU-1',quantity=2,price=100,sellerId='normal',runId=root.name))
    record('admission_reopened',orderId=readmitted['orderId'])
    wait_for(lambda:len(sql('SELECT event_id FROM partner_effect'))==201,timeout=30)
    expected=[json.loads(x['payload']) for x in sql("SELECT payload FROM outbox_event WHERE topic='partner-order-requests' ORDER BY id",'order_db')]
    if len(expected)!=201:raise AssertionError('Recovered admission did not produce exactly one more event')
    save(root/'expected.json',expected)
    wait_for(lambda:http('http://localhost:8090/observe').get('committedRemaining')==0,timeout=10)
    remote,effects,states=snapshot(root);checked=check(expected,remote,effects,states)
    source_orders=sql('SELECT order_id,order_status,partner_sequence,quantity,price FROM orders','order_db')
    source_stock=int(sql("SELECT stock_quantity FROM inventories WHERE product_cd='SKU-1'",'inventory_db')[0]['stock_quantity'])
    save(root/'source-final.json',dict(orders=source_orders,stock=source_stock))
    if len(source_orders)!=201 or source_stock!=598 or any(x['order_status']!='CONFIRMED' or x['partner_sequence']!=1 or x['quantity']!=2 or x['price']!=100 for x in source_orders):
        checked['errors'].append('Source mutation rollback or inventory effect mismatch')
        checked['passed']=False
    checked['sourceStock']=source_stock
    checked['acceptedUnfinished']=200;checked['rejected201st']=True;checked['rejectedMutationRolledBack']=True;checked['postDrainReadmitted']=True
    save(root/'checker.json',checked)
    (root/'collector.stop').touch();collector.wait(timeout=8)
    report(root,expected,checked);record('completed',passed=checked['passed'])
    print(root.name,json.dumps(checked),flush=True)
    if not checked['passed']:raise AssertionError(checked)
except Exception as e:
    save(root/'failure.json',dict(error=repr(e),time=now()));raise
finally:
    if collector and collector.poll() is None:(root/'collector.stop').touch();stop(collector)
    for process in reversed(processes):stop(process)
    for process in PROCESSES:stop(process)
    for handle in HANDLES:handle.close()
