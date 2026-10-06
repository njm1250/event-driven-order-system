#!/usr/bin/env python3
"""Local reproducible Kafka/MySQL experiment controller. All evidence must live outside Git."""
import argparse
import hashlib
import json
import math
import os
import platform
import signal
import statistics
import subprocess
import sys
import threading
import time
import urllib.request
import urllib.error
import uuid
from pathlib import Path
from checker import check

REPO = Path(__file__).resolve().parents[1]
COMPOSE = ['docker','compose','-p','partner-isolation','-f',str(REPO/'docker-compose.experiment.yml')]
PROCESSES = []
HANDLES = []
JAVA_RUNTIME = None

def now():
    return int(time.time()*1000)

def save(path, value):
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2))

def command(args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs)

def sql(query, database='partner_db'):
    out = command(COMPOSE+['exec','-T','mysql','mysql','-uroot','-plabpassword','--batch','--raw',database,'-e',query],stderr=subprocess.DEVNULL)
    lines = out.splitlines()
    if not lines:
        return []
    result = []
    for line in lines[1:]:
        row = {}
        for key,value in zip(lines[0].split('\t'),line.split('\t')):
            if value == 'NULL': value = None
            elif key in {'order_id','seq','completed_at','attempts','quantity','partner_sequence','stock_quantity','delta'}: value = int(value)
            elif key == 'price': value = float(value)
            row[key] = value
        result.append(row)
    return result

def http(url, data=None, method=None, timeout=8):
    encoded = None if data is None else json.dumps(data).encode()
    request=urllib.request.Request(url, data=encoded, headers={'Content-Type':'application/json'},method=method)
    with urllib.request.urlopen(request,timeout=timeout) as response:
        return json.load(response)

def wait_for(predicate, timeout=40, label='condition'):
    deadline=time.monotonic()+timeout
    last=None
    while time.monotonic()<deadline:
        try:
            value=predicate()
            if value: return value
        except Exception as e:
            last=e
        time.sleep(0.1)
    raise TimeoutError(f'{label}, last error: {last}')

def launch(args, logfile, env=None):
    handle=logfile.open('a')
    HANDLES.append(handle)
    process=subprocess.Popen(args,cwd=REPO,stdout=handle,stderr=subprocess.STDOUT,env=env)
    PROCESSES.append(process)
    if args[0]=='java' or any('mock-partner-api/server.py' in str(x) for x in args):
        registry=logfile.parent/'process-pids.json'
        records=json.loads(registry.read_text()) if registry.exists() else []
        records.append(dict(pid=process.pid,command=args[0],log=logfile.name,startedAt=now()))
        save(registry,records)
    return process

def stop(process, kill=False):
    if process and process.poll() is None:
        process.kill() if kill else process.terminate()
        try: process.wait(timeout=8)
        except subprocess.TimeoutExpired: process.kill(); process.wait()

def java(module, root, extra=None, jar=None):
    env=os.environ.copy()
    env.update(SPRING_KAFKA_BOOTSTRAP_SERVERS='localhost:39092',SPRING_DATASOURCE_USERNAME='root',
               SPRING_DATASOURCE_PASSWORD='labpassword',APP_KAFKA_PARTITIONS='1',CODE_VERSION=command(['git','rev-parse','HEAD']).strip(),
               EXPERIMENT_HOOK_DIR=str(root/'hooks'))
    name={'order-service':'order_db','inventory-service':'inventory_db','notification-service':'notification_db','partner-integration-service':'partner_db'}[module]
    env['SPRING_DATASOURCE_URL']=f'jdbc:mysql://localhost:13306/{name}'
    if extra: env.update({k:str(v) for k,v in extra.items()})
    target=jar or REPO/module/'build/libs/app.jar'
    return launch(['java','-Xms64m','-Xmx192m','-XX:ActiveProcessorCount=4','-jar',str(target)],root/(module+'.log'),env)

def new_root(base, name):
    root=base/(time.strftime('%Y%m%d-%H%M%S')+'-'+name+'-'+uuid.uuid4().hex[:5]);root.mkdir()
    (root/'hooks').mkdir()
    return root

def metadata(root, settings):
    global JAVA_RUNTIME
    if JAVA_RUNTIME is None:JAVA_RUNTIME = subprocess.check_output(['java','-version'],stderr=subprocess.STDOUT,text=True).strip()
    save(root/'manifest.json',dict(settings=settings,gitCommit=command(['git','rev-parse','HEAD']).strip(),
        gitDiffSha256=hashlib.sha256(command(['git','diff']).encode()).hexdigest(),host=platform.platform(),
        jvm='-Xms64m -Xmx192m -XX:ActiveProcessorCount=4',jvmProcessorHint=4,workers=4,sellerConcurrency=2,dbPool=4,
        normalSellerSloMs=1500,backlogLimit=200,retainedRowsLimit=2000,rawObservationLimitBytes=8388608,
        jarSha256=hashlib.sha256((REPO/'partner-integration-service/build/libs/app.jar').read_bytes()).hexdigest(),
        scriptsSha256={str(x.relative_to(REPO)):hashlib.sha256(x.read_bytes()).hexdigest() for x in (REPO/'experiments').glob('*.py')},
        seed=1250,python=sys.version,javaRuntime=JAVA_RUNTIME,startedAt=now()))
    (root/'code.diff').write_text(command(['git','diff']))
    (root/'compose.yaml').write_text((REPO/'docker-compose.experiment.yml').read_text())
    (root/'effective.properties').write_text((REPO/'partner-integration-service/src/main/resources/application.properties').read_text())

def baseline(base):
    root=base/'baseline'; root.mkdir(exist_ok=True)
    if not (root/'order.jar').exists():
        source=root/'source';source.mkdir(exist_ok=True)
        archive=subprocess.check_output(['git','archive','baseline-2026-10-06-d28e898'])
        subprocess.run(['tar','-xf','-','-C',str(source)],input=archive,check=True)
        with (root/'build.log').open('w') as output:
            subprocess.run(['bash','gradlew','test',':order-service:bootJar',':inventory-service:bootJar',':notification-service:bootJar','--no-daemon'],cwd=source,stdout=output,stderr=subprocess.STDOUT,check=True)
        import shutil
        for module,target in [('order-service','order.jar'),('inventory-service','inventory.jar'),('notification-service','notification.jar')]:
            shutil.copyfile(source/module/'build/libs/app.jar',root/target)
    command(COMPOSE+['exec','-T','mysql','mysql','-uroot','-plabpassword','-e','DROP DATABASE order_db; CREATE DATABASE order_db; DROP DATABASE inventory_db; CREATE DATABASE inventory_db; DROP DATABASE notification_db; CREATE DATABASE notification_db;'],stderr=subprocess.DEVNULL)
    processes=[]
    try:
        for module,old in [('order-service','order.jar'),('inventory-service','inventory.jar'),('notification-service','notification.jar')]:
            processes.append(java(module,root,{'APP_OUTBOX_ENABLED':'true'},root/old))
        def ready():
            try: return http('http://localhost:8081/orders/1')
            except urllib.error.HTTPError as e: return e.code == 404
        wait_for(ready,timeout=40,label='baseline API ready')
    except TimeoutError:
        # Missing order is expected; use HTTP status as readiness instead.
        pass
    try:
        wait_for(lambda:sql('SHOW TABLES','inventory_db'),label='baseline schema')
        sql("INSERT INTO inventories(product_cd,stock_quantity,version) VALUES('SKU-1',1000,0)",'inventory_db')
        orders=[http('http://localhost:8081/orders',dict(productCode='SKU-1',quantity=2,price=100)) for _ in range(4)]
        ids=[x['orderId'] for x in orders]
        states=wait_for(lambda: (lambda x:x if all(r['order_status']=='CONFIRMED' for r in x) and len(x)==4 else None)(sql('SELECT order_id,order_status FROM orders','order_db')),label='baseline confirmation')
        save(root/'actual-run.json',dict(orders=orders,states=states,inventory=sql('SELECT * FROM inventories','inventory_db'),outbox=sql('SELECT event_id,status FROM outbox_event','order_db'),testCount=5,legacyRawResultsAvailable=False,effectiveOutboxEnabled=True,originalDefaultOutboxEnabled=False))
    finally:
        for process in processes:stop(process)
        command(COMPOSE+['exec','-T','mysql','mysql','-uroot','-plabpassword','-e','DROP DATABASE order_db; CREATE DATABASE order_db; DROP DATABASE inventory_db; CREATE DATABASE inventory_db; DROP DATABASE notification_db; CREATE DATABASE notification_db;'],stderr=subprocess.DEVNULL)
    print('baseline preserved',flush=True)

def dataset(run_id, orders=24, operations=3):
    values=[]
    # Each phase is interleaved between sellers; every event explicitly goes to partition 0.
    for seq in range(1,operations+1):
        for i in range(orders):
            values.append(dict(eventId=str(uuid.uuid5(uuid.NAMESPACE_URL,f'{run_id}/{i}/{seq}')),runId=run_id,
                sellerId='slow' if i%2==0 else 'normal',orderId=i+1,sequence=seq,
                operation=['CREATE','CHANGE','CANCEL'][seq-1],occurredAt=now(),schemaVersion=1,
                quantity=seq+1,price=float(100+seq)))
    return values

def snapshot(root):
    remote=http('http://localhost:8099/snapshot')
    effects=sql('SELECT * FROM partner_effect ORDER BY completed_at,event_id')
    orders=sql('SELECT * FROM partner_order ORDER BY seller_id,order_id')
    save(root/'remote.json',remote);save(root/'db-effects.json',effects);save(root/'db-orders.json',orders)
    save(root/'inbox.json',sql('SELECT event_id,seller_id,order_id,seq,state,attempts,received_at,done_at FROM inbox'))
    save(root/'db-io-after.json',sql("SELECT OBJECT_NAME,COUNT_READ,COUNT_WRITE,COUNT_FETCH,COUNT_INSERT,COUNT_UPDATE,COUNT_DELETE FROM performance_schema.table_io_waits_summary_by_table WHERE OBJECT_SCHEMA='partner_db'"))
    sizes=sql("SELECT TABLE_NAME,DATA_LENGTH,INDEX_LENGTH,TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_SCHEMA='partner_db'")
    save(root/'storage.json',sizes)
    save(root/'final-observe.json',http('http://localhost:8090/observe'))
    (root/'infra-resources.jsonl').write_text(command(COMPOSE+['stats','--no-stream','--format','json']))
    return remote,effects,orders

def percentile(values, quantile):
    return sorted(values)[max(0,math.ceil(quantile*len(values))-1)] if values else None

def report(root, expected, result):
    remote=json.loads((root/'remote.json').read_text())
    rows=[]
    for seller in ['normal','slow']:
        latencies=[x['effect_at']-x['occurred_at'] for x in remote['effects'] if x['seller_id']==seller]
        rows.append(dict(seller=seller,p95Ms=percentile(latencies,.95),p99Ms=percentile(latencies,.99),maxMs=max(latencies,default=0)))
    observations=[json.loads(x) for x in (root/'observations.jsonl').read_text().splitlines()] if (root/'observations.jsonl').exists() else []
    traces=[]
    for line in (root/'partner-integration-service.log').read_text().splitlines():
        if line.startswith('TRACE '):traces.append(json.loads(line[6:]))
    save(root/'traces.json',traces)
    controller=json.loads((root/'controller.json').read_text())
    removed=next((x['time'] for x in controller if x['action']=='fault_removed'),None)
    external_slow_done=max((x['effect_at'] for x in remote['effects'] if x['seller_id']=='slow'),default=0)
    slow_done=max((x['time'] for x in traces if x['stage']=='business_commit' and x.get('sellerId')=='slow'),default=external_slow_done)
    dbwait=[x.get('waitMs',0) for x in traces if x['stage']=='db_acquired']
    external=[x.get('durationMs',0) for x in traces if x['stage']=='external_result']
    errors=[x for x in traces if x['stage']=='external_error']
    service_samples=[x['service'] for x in observations if x.get('service')]
    metrics=dict(latency=rows,externalRecoveryMs=max(0,external_slow_done-removed) if removed else None,recoveryMs=max(0,slow_done-removed) if removed else None,
        oldestBySeller={s:max((x.get('oldestMs',{}).get(s,0) for x in observations),default=0) for s in ['normal','slow']},
        peakDbWaiting=max((x.get('dbWaiting',0) for x in service_samples),default=0),
        peakHeapUsed=max((x.get('heapUsed',0) for x in service_samples),default=0),
        peakActive=max((x.get('active',0) for x in service_samples),default=0),
        dbAcquireP95Ms=percentile(dbwait,.95),externalP95Ms=percentile(external,.95),externalErrors=len(errors),
        pausedSamples=sum(x.get('paused',False) for x in service_samples),traceBytes=(root/'partner-integration-service.log').stat().st_size,
        lastCpuNanos=json.loads((root/'final-observe.json').read_text()).get('cpuNanos'),
        peakProcessRssKiB=max((x.get('processRssKiB',0) for x in observations),default=0),
        observerSampleP95Ms=percentile([x['sampleMs'] for x in observations],.95),
        measuredNormalSloPassed=(rows[0]['p99Ms'] or float('inf'))<=1500,checker=result)
    before=json.loads((root/'before-load-observe.json').read_text()) if (root/'before-load-observe.json').exists() else {}
    cpu_by_instance={}
    for sample in service_samples+[json.loads((root/'final-observe.json').read_text())]:
        if sample.get('instance'):
            cpu_by_instance[sample['instance']]=max(cpu_by_instance.get(sample['instance'],0),sample.get('cpuNanos',0))
    metrics['sampledWorkloadCpuLowerBoundNanos']=sum(cpu_by_instance.values())-before.get('cpuNanos',0)
    metrics['workloadCpuNanos']=metrics['sampledWorkloadCpuLowerBoundNanos'] if len(cpu_by_instance)==1 else None
    metrics['cpuInstances']=len(cpu_by_instance)
    memory_samples=[]
    for item in observations:
        containers=item.get('infra',{})
        if item.get('processRssKiB') is not None and len(containers)==2 and all(x.get('memoryWithoutInactiveFileBytes') is not None for x in containers.values()):
            memory_samples.append(item['processRssKiB']*1024+item.get('collectorPeakRssBytes',0)+sum(x['memoryWithoutInactiveFileBytes'] for x in containers.values()))
    metrics['peakMeasuredStackMemoryBytes']=max(memory_samples) if memory_samples else None
    metrics['infraMemorySamples']=len(memory_samples)
    metrics['externalSqliteBytes']=(root/'external.sqlite').stat().st_size + sum(x.stat().st_size for x in root.glob('external.sqlite-*'))
    metrics['rawEvidenceBytes']=sum(x.stat().st_size for x in root.rglob('*') if x.is_file())
    if (root/'input-times.json').exists():
        inputs=json.loads((root/'input-times.json').read_text())
        source_rows=[]
        for seller in ['normal','slow']:
            times=[row['effect_at']-inputs[f'{row["order_id"]}/{row["seq"]}'] for row in remote['effects'] if row['seller_id']==seller]
            source_rows.append(dict(seller=seller,p95Ms=percentile(times,.95),p99Ms=percentile(times,.99)))
        metrics['sourceApiLatency']=source_rows
    save(root/'summary.json',metrics)
    return metrics

def run_case(base, mode, scenario, repeat, observe=True):
    root=new_root(base,f'{mode}-{scenario}-r{repeat}')
    topic='partner-'+uuid.uuid4().hex[:16]; group=topic+'-group'
    settings=dict(mode=mode,scenario=scenario,repeat=repeat,topic=topic,group=group,observe=observe,backlogLimit=8 if scenario=='backlog' else 200)
    metadata(root,settings)
    sql('DELETE FROM inbox; DELETE FROM partner_effect; DELETE FROM partner_order') if sql('SHOW TABLES') else None
    mock=launch([sys.executable,str(REPO/'experiments/mock-partner-api/server.py'),'--database',str(root/'external.sqlite')],root/'mock.log')
    extra=dict(PARTNER_MODE=mode,PARTNER_TOPIC=topic,PARTNER_GROUP=group)
    if scenario=='rebalance':extra['SPRING_KAFKA_CONSUMER_PROPERTIES_MAX_POLL_INTERVAL_MS']=2000
    if scenario=='backlog':extra['APP_BACKLOG_LIMIT']=8
    if not observe:extra['APP_TRACE_ENABLED']='false'
    service=None;collector=None; control=[]; threads=[]; expected=[]
    def record(action, **detail):
        control.append(dict(time=now(),action=action,**detail));save(root/'controller.json',control)
    def clear_fault():
        time.sleep(6)
        http('http://localhost:8099/control',dict(sellerId='slow',delayMs=0,failureCount=0))
        record('fault_removed')
    try:
        wait_for(lambda:http('http://localhost:8099/health'),label='mock ready')
        service=java('partner-integration-service',root,extra)
        wait_for(lambda:http('http://localhost:8090/observe').get('assigned',0)>0,label='partner assignment ready')
        http('http://localhost:8090/load',[])
        # Allow assignment and capture 1s of fault-free data before injection.
        if observe:
            collector=launch([sys.executable,str(REPO/'experiments/collector.py'),'--directory',str(root),'--pids',f'{service.pid},{mock.pid}'],root/'collector.log')
        time.sleep(1)
        save(root/'before-load-observe.json',http('http://localhost:8090/observe'))
        save(root/'db-io-before.json',sql("SELECT OBJECT_NAME,COUNT_READ,COUNT_WRITE,COUNT_FETCH,COUNT_INSERT,COUNT_UPDATE,COUNT_DELETE FROM performance_schema.table_io_waits_summary_by_table WHERE OBJECT_SCHEMA='partner_db'"))
        record('workload_started')
        expected=dataset(root.name,24,3)
        if scenario=='hotkey':
            for event in expected:event['sellerId']='slow' if event['orderId']==1 else 'normal'
        save(root/'expected.json',[])
        if scenario in {'api','backlog','broker-kill','hotkey'}:
            http('http://localhost:8099/control',dict(sellerId='slow',delayMs=600,failureCount=1000 if scenario=='hotkey' else 0))
            record('fault_injected',kind='seller_api_delay',delayMs=600)
            thread=threading.Thread(target=clear_fault);thread.start();threads.append(thread)
        elif scenario=='retry':
            http('http://localhost:8099/control',dict(sellerId='slow',failureCount=3))
            record('fault_injected',kind='seller_error',failureCount=3)
            thread=threading.Thread(target=clear_fault);thread.start();threads.append(thread)
        elif scenario=='response-loss':
            http('http://localhost:8099/control',dict(sellerId='slow',dropResponse=True))
            record('fault_injected',kind='response_loss')
        elif scenario=='db':
            http('http://localhost:8090/fault/db',dict(durationMs=3000))
            record('fault_injected',kind='shared_db_pool',durationMs=3000)
        gate=None
        if scenario in {'ack-kill','ack-release','inbox-kill','worker-kill','external-kill','business-before-kill','inbox-before-kill','rebalance'}:
            gate={'inbox-kill':'inbox_commit','worker-kill':'worker_commit','external-kill':'external_success','business-before-kill':'business_before_commit','inbox-before-kill':'inbox_before_commit'}.get(scenario,'business_commit')
            (root/'hooks'/f'{gate}.arm').touch()
            record('gate_armed',gate=gate)
        placements=[]
        # All modes get identical event count, interleaving and 100ms batch arrival cadence.
        for i in range(0,len(expected),8):
            for event in expected[i:i+8]:event['occurredAt']=now()
            save(root/'expected.json',expected[:i+8])
            placements.extend(http('http://localhost:8090/load',expected[i:i+8]))
            time.sleep(.1)
        save(root/'placements.json',placements)
        if {x['partition'] for x in placements}!={0}:raise AssertionError('Sellers did not share partition 0')
        if scenario=='broker-kill':
            record('broker_killed')
            command(COMPOSE+['kill','-s','SIGKILL','kafka'])
            time.sleep(2)
            if not (root/'observations.jsonl').exists():raise AssertionError('Pre-crash observations missing')
            command(COMPOSE+['start','kafka'])
            wait_for(lambda:command(COMPOSE+['exec','-T','kafka','/opt/kafka/bin/kafka-broker-api-versions.sh','--bootstrap-server','localhost:9092']),timeout=30,label='broker restarted')
            record('broker_restarted')
        if gate:
            reached=wait_for(lambda:(root/'hooks'/f'{gate}.reached').exists(),label='actual boundary')
            time.sleep(4 if scenario=='rebalance' else 2)
            save(root/'at-boundary-observe.json',http('http://localhost:8090/observe'))
            save(root/'at-boundary-effects.json',sql('SELECT * FROM partner_effect'))
            save(root/'at-boundary-inbox.json',sql('SELECT event_id,state,kafka_offset FROM inbox'))
            record('gate_observed',gate=gate,eventId=(root/'hooks'/f'{gate}.reached').read_text().splitlines()[0])
            if scenario.endswith('kill'):
                stop(service,kill=True);record('process_killed',gate=gate)
                (root/'hooks'/f'{gate}.arm').unlink()
                service=java('partner-integration-service',root,extra)
                wait_for(lambda:http('http://localhost:8090/observe'),label='restarted service')
                record('process_restarted')
            else:
                (root/'hooks'/f'{gate}.arm').unlink();record('gate_released')
        if scenario=='redelivery':
            http('http://localhost:8090/load',expected)
            record('duplicate_input')
        wait_for(lambda:len(sql('SELECT event_id FROM partner_effect'))==len(expected),timeout=90,label='all business effects')
        for thread in threads:thread.join()
        wait_for(lambda:http('http://localhost:8090/observe').get('committedRemaining')==0,timeout=15,label='broker commit drained')
        time.sleep(.5)
        remote,effects,orders=snapshot(root)
        result=check(expected,remote,effects,orders);save(root/'checker.json',result)
        if collector:
            (root/'collector.stop').touch();collector.wait(timeout=8)
        metrics=report(root,expected,result)
        record('completed',passed=result['passed'])
        print(root.name,json.dumps(dict(passed=result['passed'],latency=metrics['latency'],peakDbWaiting=metrics['peakDbWaiting'],recoveryMs=metrics['recoveryMs'])),flush=True)
        if not result['passed']:raise AssertionError(result)
        return root
    except Exception as e:
        save(root/'failure.json',dict(error=repr(e),time=now()))
        print('FAILED',root,str(e),flush=True)
        raise
    finally:
        for thread in threads:thread.join(timeout=8)
        if collector and collector.poll() is None:(root/'collector.stop').touch();stop(collector)
        stop(service);stop(mock)

if __name__ == '__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--evidence',default='/Users/jun/Desktop/experiment-evidence/2026-10-06')
    p.add_argument('--baseline',action='store_true')
    p.add_argument('--mode',choices=['sequential','async','inbox'])
    p.add_argument('--scenario',default='api',choices=['clean','api','db','ack-kill','ack-release','inbox-kill','worker-kill','external-kill','response-loss','redelivery','retry','backlog','business-before-kill','inbox-before-kill','rebalance','broker-kill','hotkey'])
    p.add_argument('--repeat',type=int,default=1)
    p.add_argument('--suite',action='store_true')
    p.add_argument('--no-observe',action='store_true')
    args=p.parse_args()
    evidence=Path(args.evidence).expanduser().resolve()
    if evidence==REPO or REPO in evidence.parents:raise SystemExit('Evidence must be outside the Git repository')
    evidence.mkdir(parents=True,exist_ok=True)
    try:
        if args.baseline:baseline(evidence)
        elif args.suite:
            for repeat in range(1,4):
                for mode in ['sequential','async','inbox']:run_case(evidence,mode,'api',repeat)
            for mode in ['sequential','async','inbox']:
                for scenario in ['db','response-loss','redelivery','retry']:run_case(evidence,mode,scenario,1)
            for mode in ['sequential','async']:
                for scenario in ['ack-release','ack-kill','external-kill','business-before-kill','rebalance']:run_case(evidence,mode,scenario,1)
            for scenario in ['inbox-kill','worker-kill','external-kill','business-before-kill','inbox-before-kill','backlog']:run_case(evidence,'inbox',scenario,1)
            for mode in ['sequential','async','inbox']:
                run_case(evidence,mode,'clean',1)
                run_case(evidence,mode,'clean',2,False)
        else:run_case(evidence,args.mode or 'sequential',args.scenario,args.repeat,not args.no_observe)
    finally:
        for process in PROCESSES:stop(process)
        for handle in HANDLES:handle.close()
