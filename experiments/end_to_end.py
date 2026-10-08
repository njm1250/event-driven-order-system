#!/usr/bin/env python3
"""Order HTTP API -> inventory transactional result -> order confirmation -> partner Kafka/API."""
import argparse
import json
import sys
import time
import urllib.error
import uuid
from pathlib import Path
from evidence_paths import validate_evidence_path, DEFAULT_EVIDENCE
from run import INBOX_VARIANTS, REPO, COMPOSE, command, sql, http, wait_for, java, launch, stop, new_root, metadata, save, snapshot, report, now, PROCESSES, HANDLES
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

def run(base, mode, boundary='none', concurrent_duplicate=False, poll_ms=100, seller_fault=False):
    reset_source()
    root=new_root(base,f'e2e-{mode}-{boundary}'+('-concurrent' if concurrent_duplicate else ''))
    metadata(root,dict(mode=mode,scenario='end-to-end',boundary=boundary,concurrentDuplicate=concurrent_duplicate,sourcePollMs=poll_ms,sellerFault=seller_fault))
    procs=[];controller=[];collector=None
    def record(action, **extra):
        controller.append(dict(time=now(),action=action,**extra));save(root/'controller.json',controller)
    def ready():
        try:return http('http://localhost:8081/orders/1')
        except urllib.error.HTTPError as e:return e.code==404
    try:
        procs.append(launch([sys.executable,str(REPO/'experiments/mock-partner-api/server.py'),'--database',str(root/'external.sqlite')],root/'mock.log'))
        wait_for(lambda:http('http://localhost:8099/health'))
        partner=dict(PARTNER_MODE=mode,PARTNER_TOPIC='partner-order-requests',PARTNER_GROUP='e2e-'+uuid.uuid4().hex)
        if mode in INBOX_VARIANTS:partner.update(PARTNER_MODE='inbox',**INBOX_VARIANTS[mode])
        procs.append(java('partner-integration-service',root,partner))
        wait_for(lambda:http('http://localhost:8090/observe'))
        if (REPO/'experiments/migrations/001-source-schema.sql').exists():
            command(COMPOSE+['exec','-T','mysql','mysql','-uroot','-plabpassword'],input=(REPO/'experiments/migrations/001-source-schema.sql').read_text(),stderr=__import__('subprocess').DEVNULL)
        order_process=java('order-service',root,dict(APP_OUTBOX_POLL_MS=poll_ms));procs.append(order_process)
        inventory_process=java('inventory-service',root,dict(APP_OUTBOX_POLL_MS=poll_ms));procs.append(inventory_process)
        wait_for(ready)
        wait_for(lambda:'partitions assigned: [inventory-order-created-0]' in (root/'inventory-service.log').read_text(),label='inventory assigned')
        wait_for(lambda:'partitions assigned: [order-stock-update-0]' in (root/'order-service.log').read_text(),label='order result assigned')
        wait_for(lambda:sql('SHOW TABLES','inventory_db'))
        sql("INSERT INTO inventories(product_cd,stock_quantity,version) VALUES('SKU-1',1000,0)",'inventory_db')
        if concurrent_duplicate:
            duplicate=java('inventory-service',root,dict(SPRING_KAFKA_CONSUMER_GROUP_ID='inventory-duplicate-'+uuid.uuid4().hex,SERVER_PORT=8086,APP_OUTBOX_ENABLED='false'))
            procs.append(duplicate)
            wait_for(lambda: 'Started InventoryServiceApplication' in (root/'inventory-service.log').read_text())
            time.sleep(3)
        collector=launch([sys.executable,str(REPO/'experiments/collector.py'),'--directory',str(root)],root/'collector.log')
        if boundary!='none':(root/'hooks'/f'{boundary}.arm').touch();record('gate_armed',gate=boundary)
        if seller_fault:
            import threading
            http('http://localhost:8099/control',dict(sellerId='slow',delayMs=600))
            record('fault_injected',kind='seller_api_delay',delayMs=600)
            def clear():
                time.sleep(6)
                http('http://localhost:8099/control',dict(sellerId='slow',delayMs=0))
                record('fault_removed')
            recovery=threading.Thread(target=clear);recovery.start()
        save(root/'before-load-observe.json',http('http://localhost:8090/observe'))
        statement_query="SELECT SCHEMA_NAME,SUM(COUNT_STAR) AS COUNT_STATEMENTS FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME IN ('order_db','inventory_db') GROUP BY SCHEMA_NAME"
        io_begin=now()
        save(root/'source-statements-before.json',sql(statement_query))
        save(root/'source-db-io-before.json',sql("SELECT OBJECT_SCHEMA,OBJECT_NAME,COUNT_READ,COUNT_WRITE,COUNT_INSERT,COUNT_UPDATE FROM performance_schema.table_io_waits_summary_by_table WHERE OBJECT_SCHEMA IN ('order_db','inventory_db')"))
        record('workload_started')
        orders=[];input_times={}
        for i in range(6):
            started=now()
            order_input=dict(productCode='SKU-1',quantity=2,price=100,sellerId='slow' if i%2==0 else 'normal',runId=root.name)
            order=http('http://localhost:8081/orders',order_input)
            orders.append(order)
            input_times[f'{order["orderId"]}/1']=started
        save(root/'input-times.json',input_times)
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
            target=order_process if boundary=='broker_ack' else inventory_process
            stop(target,kill=True);record('process_killed',gate=boundary,eventId=event)
            (root/'hooks'/f'{boundary}.arm').unlink()
            target=java('order-service' if boundary=='broker_ack' else 'inventory-service',root,dict(APP_OUTBOX_POLL_MS=poll_ms));procs.append(target)
            record('process_restarted')
        wait_for(lambda: all(http(f'http://localhost:8081/orders/{o["orderId"]}')['status']=='CONFIRMED' for o in orders),timeout=60,label='source confirmations')
        for o in orders:
            input_times[f'{o["orderId"]}/2']=now()
            http(f'http://localhost:8081/orders/{o["orderId"]}',dict(quantity=3,price=120),method='PATCH')
            input_times[f'{o["orderId"]}/3']=now()
            http(f'http://localhost:8081/orders/{o["orderId"]}/cancel',{},method='POST')
            save(root/'input-times.json',input_times)
        records=sql("SELECT payload FROM outbox_event WHERE topic='partner-order-requests' ORDER BY id",'order_db')
        expected=[]
        source_sellers={o['orderId']:'slow' if i%2==0 else 'normal' for i,o in enumerate(orders)}
        for row in records:
            emitted=json.loads(row['payload'])
            seq=emitted['sequence']
            wanted=dict(emitted, sellerId=source_sellers[emitted['orderId']],runId=root.name,
                        operation={1:'CREATE',2:'CHANGE',3:'CANCEL'}[seq],
                        quantity=2 if seq==1 else 3,price=100.0 if seq==1 else 120.0,schemaVersion=1)
            if emitted!=wanted:raise AssertionError('Source Outbox payload differs from the HTTP input contract')
            expected.append(wanted)
        save(root/'expected.json',expected)
        if len(expected)!=18:raise AssertionError('Source did not create exactly three partner events per order')
        if {(e['orderId'],e['sequence']) for e in expected}!={(o['orderId'],s) for o in orders for s in [1,2,3]}:
            raise AssertionError('Source events do not cover every HTTP order and operation')
        wait_for(lambda:len(sql('SELECT event_id FROM partner_effect'))==18,timeout=60,label='end-to-end effects')
        wait_for(lambda:http('http://localhost:8090/observe').get('committedRemaining')==0,timeout=15,label='end-to-end offset drain')
        if seller_fault:recovery.join()
        remote,effects,states=snapshot(root)
        save(root/'source-db-io-after.json',sql("SELECT OBJECT_SCHEMA,OBJECT_NAME,COUNT_READ,COUNT_WRITE,COUNT_INSERT,COUNT_UPDATE FROM performance_schema.table_io_waits_summary_by_table WHERE OBJECT_SCHEMA IN ('order_db','inventory_db')"))
        save(root/'source-statements-after.json',sql(statement_query))
        save(root/'source-io-window.json',dict(startedAt=io_begin,finishedAt=now()))
        checked=check(expected,remote,effects,states)
        stock=sql("SELECT stock_quantity FROM inventories WHERE product_cd='SKU-1'",'inventory_db')[0]['stock_quantity']
        history=sql('SELECT event_id,order_id,delta FROM stock_history','inventory_db')
        final_orders=sql('SELECT order_id,order_status,partner_sequence,quantity,price FROM orders','order_db')
        checked['sourceStock']=stock;checked['sourceHistoryCount']=len(history)
        if stock!=988 or len(history)!=6 or any(x['delta']!=-2 for x in history) or {x['order_id'] for x in history}!={o['orderId'] for o in orders}:checked['errors'].append('Inventory effects duplicated, missing or attributed to another order')
        if any(x['order_status']!='CANCELLED' or x['partner_sequence']!=3 or x['quantity']!=3 or x['price']!=120 for x in final_orders):checked['errors'].append('Source sequence/final state mismatch')
        checked['passed']=not checked['errors']
        save(root/'source-final.json',dict(orders=final_orders,history=history,stock=stock))
        save(root/'checker.json',checked)
        idle_start=now()
        idle_before=sql(statement_query)
        time.sleep(2)
        idle_after=sql(statement_query)
        save(root/'idle-poll-statements.json',dict(startedAt=idle_start,finishedAt=now(),before=idle_before,after=idle_after))
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
    p=argparse.ArgumentParser();p.add_argument('--evidence',default=str(DEFAULT_EVIDENCE));p.add_argument('--mode',default='inbox',choices=['sequential','async','inbox','inbox-batch','inbox-lean','circuit-breaker','retry-topic','parallel-consumer']);p.add_argument('--boundary',default='none',choices=['none','broker_ack','inventory_broker_ack']);p.add_argument('--poll-ms',type=int,default=100);p.add_argument('--seller-fault',action='store_true');p.add_argument('--concurrent-duplicate',action='store_true');args=p.parse_args()
    path=Path(args.evidence).resolve()
    validate_evidence_path(path)
    path.mkdir(parents=True,exist_ok=True)
    try:run(path,args.mode,args.boundary,args.concurrent_duplicate,args.poll_ms,args.seller_fault)
    finally:
        for p in PROCESSES:stop(p)
        for f in HANDLES:f.close()
