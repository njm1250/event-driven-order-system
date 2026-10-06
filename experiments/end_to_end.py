#!/usr/bin/env python3
"""Order HTTP API -> inventory transactional result -> order confirmation -> partner Kafka/API."""
import argparse
import json
import sys
import time
import urllib.error
import uuid
from pathlib import Path
from run import REPO, COMPOSE, command, sql, http, wait_for, java, launch, stop, new_root, metadata, save, snapshot, report, now, PROCESSES, HANDLES
from checker import check

LEGACY_TOPICS=['inventory-order-created','order-stock-update','order-stock-update-failed','partner-order-requests',
               'inventory-order-created.DLT','order-stock-update.DLT','order-stock-update-failed.DLT']

def reset_source():
    for topic in LEGACY_TOPICS:
        command(COMPOSE+['exec','-T','kafka','/opt/kafka/bin/kafka-topics.sh','--bootstrap-server','kafka:9092','--delete','--if-exists','--topic',topic])
    command(COMPOSE+['exec','-T','mysql','mysql','-uroot','-plabpassword','-e',
        'DROP DATABASE order_db; CREATE DATABASE order_db; DROP DATABASE inventory_db; CREATE DATABASE inventory_db;'],stderr=__import__('subprocess').DEVNULL)
    sql('DELETE FROM inbox; DELETE FROM partner_effect; DELETE FROM partner_order')
    time.sleep(1)

def run(base, mode, boundary='none', concurrent_duplicate=False):
    reset_source()
    root=new_root(base,f'e2e-{mode}-{boundary}'+('-concurrent' if concurrent_duplicate else ''))
    metadata(root,dict(mode=mode,scenario='end-to-end',boundary=boundary,concurrentDuplicate=concurrent_duplicate))
    procs=[];controller=[];collector=None
    def record(action, **extra):
        controller.append(dict(time=now(),action=action,**extra));save(root/'controller.json',controller)
    def ready():
        try:return http('http://localhost:8081/orders/1')
        except urllib.error.HTTPError as e:return e.code==404
    try:
        procs.append(launch([sys.executable,str(REPO/'experiments/mock-partner-api/server.py'),'--database',str(root/'external.sqlite')],root/'mock.log'))
        wait_for(lambda:http('http://localhost:8099/health'))
        procs.append(java('partner-integration-service',root,dict(PARTNER_MODE=mode,PARTNER_TOPIC='partner-order-requests',PARTNER_GROUP='e2e-'+uuid.uuid4().hex)))
        wait_for(lambda:http('http://localhost:8090/observe'))
        if (REPO/'experiments/migrations/001-source-schema.sql').exists():
            command(COMPOSE+['exec','-T','mysql','mysql','-uroot','-plabpassword'],input=(REPO/'experiments/migrations/001-source-schema.sql').read_text(),stderr=__import__('subprocess').DEVNULL)
        order=java('order-service',root);procs.append(order)
        inventory=java('inventory-service',root);procs.append(inventory)
        wait_for(ready)
        wait_for(lambda:sql('SHOW TABLES','inventory_db'))
        sql("INSERT INTO inventories(product_cd,stock_quantity,version) VALUES('SKU-1',1000,0)",'inventory_db')
        if concurrent_duplicate:
            duplicate=java('inventory-service',root,dict(SPRING_KAFKA_CONSUMER_GROUP_ID='inventory-duplicate-'+uuid.uuid4().hex,SERVER_PORT=8086,APP_OUTBOX_ENABLED='false'))
            procs.append(duplicate)
            wait_for(lambda: 'Started InventoryServiceApplication' in (root/'inventory-service.log').read_text())
            time.sleep(3)
        collector=launch([sys.executable,str(REPO/'experiments/collector.py'),'--directory',str(root)],root/'collector.log')
        if boundary!='none':(root/'hooks'/f'{boundary}.arm').touch();record('gate_armed',gate=boundary)
        orders=[http('http://localhost:8081/orders',dict(productCode='SKU-1',quantity=2,price=100,sellerId='slow' if i%2==0 else 'normal',runId=root.name)) for i in range(6)]
        save(root/'source-input.json',orders)
        if boundary!='none':
            wait_for(lambda:(root/'hooks'/f'{boundary}.reached').exists(),label='broker acknowledged Outbox')
            save(root/'at-boundary-order-outbox.json',sql('SELECT * FROM outbox_event','order_db'))
            save(root/'at-boundary-inventory-outbox.json',sql('SELECT * FROM outbox_event','inventory_db'))
            # Capture actual broker records independently of producer success logs.
            import subprocess
            topic='inventory-order-created' if boundary=='broker_ack' else 'order-stock-update'
            captured=subprocess.run(COMPOSE+['exec','-T','kafka','/opt/kafka/bin/kafka-console-consumer.sh','--bootstrap-server','kafka:9092','--topic',topic,'--partition','0','--from-beginning','--timeout-ms','3000'],capture_output=True,text=True)
            (root/'at-boundary-broker-records.jsonl').write_text(captured.stdout)
            event=(root/'hooks'/f'{boundary}.reached').read_text().splitlines()[0]
            if event not in captured.stdout:raise AssertionError('Gate event absent from actual broker records')
            target=order if boundary=='broker_ack' else inventory
            stop(target,kill=True);record('process_killed',gate=boundary,eventId=event)
            (root/'hooks'/f'{boundary}.arm').unlink()
            target=java('order-service' if boundary=='broker_ack' else 'inventory-service',root);procs.append(target)
            record('process_restarted')
        wait_for(lambda: all(http(f'http://localhost:8081/orders/{o["orderId"]}')['status']=='CONFIRMED' for o in orders),timeout=60,label='source confirmations')
        for o in orders:
            http(f'http://localhost:8081/orders/{o["orderId"]}',dict(quantity=3,price=120),method='PATCH')
            http(f'http://localhost:8081/orders/{o["orderId"]}/cancel',{},method='POST')
        records=sql("SELECT payload FROM outbox_event WHERE topic='partner-order-requests' ORDER BY id",'order_db')
        expected=[json.loads(x['payload']) for x in records];save(root/'expected.json',expected)
        if len(expected)!=18:raise AssertionError('Source did not create exactly three partner events per order')
        wait_for(lambda:len(sql('SELECT event_id FROM partner_effect'))==18,timeout=60,label='end-to-end effects')
        wait_for(lambda:http('http://localhost:8090/observe').get('committedRemaining')==0,timeout=15,label='end-to-end offset drain')
        remote,effects,states=snapshot(root)
        checked=check(expected,remote,effects,states)
        stock=sql("SELECT stock_quantity FROM inventories WHERE product_cd='SKU-1'",'inventory_db')[0]['stock_quantity']
        history=sql('SELECT event_id,order_id,delta FROM stock_history','inventory_db')
        final_orders=sql('SELECT order_id,order_status,partner_sequence,quantity,price FROM orders','order_db')
        checked['sourceStock']=stock;checked['sourceHistoryCount']=len(history)
        if stock!=988 or len(history)!=6 or any(x['delta']!=-2 for x in history):checked['errors'].append('Inventory effects duplicated or missing')
        if any(x['order_status']!='CANCELLED' or x['partner_sequence']!=3 for x in final_orders):checked['errors'].append('Source sequence/final state mismatch')
        checked['passed']=not checked['errors']
        save(root/'source-final.json',dict(orders=final_orders,history=history,stock=stock))
        save(root/'checker.json',checked)
        # Check in exact schema creation separately from evidence; validate on later starts.
        migration=REPO/'experiments/migrations/001-source-schema.sql'
        if not migration.exists():
            migration.parent.mkdir(exist_ok=True)
            text='-- Version 001: explicit experiment schema; fresh databases only.\n'
            for database,tables in [('order_db',['orders','outbox_event']),('inventory_db',['inventories','stock_history','outbox_event'])]:
                text+=f'USE {database};\n'
                for table in tables:
                    raw=command(COMPOSE+['exec','-T','mysql','mysql','-uroot','-plabpassword','--batch',database,'-e',f'SHOW CREATE TABLE {table}'],stderr=__import__('subprocess').DEVNULL)
                    ddl=raw.splitlines()[1].split('\t',1)[1].replace('\\n','\n')
                    text+=ddl.replace('CREATE TABLE ', 'CREATE TABLE IF NOT EXISTS ',1)+';\n'
            migration.write_text(text)
        (root/'collector.stop').touch();collector.wait(timeout=8)
        report(root,expected,checked);record('completed',passed=checked['passed'])
        print(root.name,json.dumps(checked),flush=True)
        if not checked['passed']:raise AssertionError(checked)
        return root
    except Exception as e:
        save(root/'failure.json',dict(error=repr(e),time=now()));raise
    finally:
        if collector and collector.poll() is None:(root/'collector.stop').touch();stop(collector)
        for p in reversed(procs):stop(p)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--evidence',default='/Users/jun/Desktop/experiment-evidence/2026-10-06');p.add_argument('--mode',default='inbox',choices=['sequential','async','inbox']);p.add_argument('--boundary',default='none',choices=['none','broker_ack','inventory_broker_ack']);p.add_argument('--concurrent-duplicate',action='store_true');args=p.parse_args()
    path=Path(args.evidence).resolve()
    if path==REPO or REPO in path.parents:raise SystemExit('Evidence must be outside repository')
    try:run(path,args.mode,args.boundary,args.concurrent_duplicate)
    finally:
        for p in PROCESSES:stop(p)
        for f in HANDLES:f.close()
