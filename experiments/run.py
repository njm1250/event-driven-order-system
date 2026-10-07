#!/usr/bin/env python3
"""Local reproducible Kafka/MySQL experiment controller. All evidence must remain untracked by Git."""
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
from evidence_paths import validate_evidence_path, DEFAULT_EVIDENCE
from checker import check
import topology
from topology import SERVICE_URL

REPO = Path(__file__).resolve().parents[1]
COMPOSE = ['docker','compose','-p','partner-isolation','-f',str(REPO/'docker-compose.experiment.yml')]
PROCESSES = []
HANDLES = []
JAVA_RUNTIME = None
# A partner API on another host (the AWS run) stays up and starts a fresh ledger per run.
PARTNER_API = os.environ.get('PARTNER_API_URL', 'http://localhost:8099')
REMOTE_PARTNER = 'localhost' not in PARTNER_API
MODES = ['sequential','async','inbox','inbox-batch','circuit-breaker','retry-topic','parallel-consumer']

def now():
    return int(time.time()*1000)

def save(path, value):
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2))

def command(args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs)

def sql(query, database='partner_db'):
    args = topology.mysql_command(database,query) if topology.MYSQL_HOST else COMPOSE+['exec','-T','mysql','mysql','-uroot','-plabpassword','--batch','--raw',database,'-e',query]
    out = command(args,stderr=subprocess.DEVNULL)
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
    host=getattr(process,'remote_host',None)
    if host:
        # No partner JVM may outlive its run on the shared partner host.
        try:topology.ssh(host,"pkill -KILL -f 'partner-integration-servic[e]/build/libs/app.jar' || true")
        except Exception:pass
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
    jvm=['java','-Xms64m','-Xmx192m','-XX:ActiveProcessorCount=4','-jar']
    if module=='partner-integration-service' and topology.PARTNER_HOST:
        return remote_java(root,module,extra,jvm)
    return launch(jvm+[str(target)],root/(module+'.log'),env)

class RemoteJvm:
    """Partner JVM on its own host. It logs to a file there so log transport does not run on the
    measured host while it works; fetch_log() copies the log back."""
    def __init__(self, host, pid, remote_log, local_log):
        self.host,self.pid,self.remote_host,self.remote_log,self.local_log=host,pid,host,remote_log,local_log
    def alive(self):
        return topology.ssh(self.host,f'kill -0 {self.pid} 2>/dev/null && echo up || echo down').strip()=='up'
    def poll(self):
        return None if self.alive() else 0
    def terminate(self):
        topology.ssh(self.host,f'kill {self.pid} 2>/dev/null || true')
    def kill(self):
        topology.ssh(self.host,f'kill -9 {self.pid} 2>/dev/null || true')
    def wait(self, timeout=None):
        deadline=time.monotonic()+(timeout or 3600)
        while self.alive():
            if time.monotonic()>deadline:raise subprocess.TimeoutExpired('remote java',timeout)
            time.sleep(0.5)
        return 0
    def fetch_log(self):
        self.local_log.write_text(topology.ssh(self.host,f'cat {self.remote_log}',timeout=120))

def remote_java(root, module, extra, jvm):
    # Same JVM flags and limits on a dedicated host.
    remote=dict(SPRING_KAFKA_BOOTSTRAP_SERVERS=topology.KAFKA_BOOTSTRAP,SPRING_DATASOURCE_URL=f'jdbc:mysql://{topology.MYSQL_HOST}:3306/partner_db',
                SPRING_DATASOURCE_USERNAME='root',SPRING_DATASOURCE_PASSWORD='labpassword',CODE_VERSION=command(['git','rev-parse','HEAD']).strip(),
                APP_REPLICATION_FACTOR=topology.REPLICATION_FACTOR,**{k:str(v) for k,v in (extra or {}).items()})
    assignments=' '.join(f"{k}='{v}'" for k,v in remote.items())
    # Separate call: a pkill inside the launch command would match that command line and kill itself.
    try:topology.ssh(topology.PARTNER_HOST,f"pkill -KILL -f '{module[:-1]}[{module[-1]}]/build/libs/app.jar' || true")
    except Exception:pass
    remote_log=f'/home/ec2-user/logs/{root.name}.log'
    # Only the nohup command goes to the background: "cd && nohup ... &" would background a subshell
    # that keeps the ssh channel open.
    pid=topology.ssh(topology.PARTNER_HOST,f"mkdir -p logs; cd repo; nohup env {assignments} {' '.join(jvm)} {module}/build/libs/app.jar >> {remote_log} 2>&1 < /dev/null & echo $!").strip()
    process=RemoteJvm(topology.PARTNER_HOST,int(pid),remote_log,root/(module+'.log'))
    PROCESSES.append(process)
    return process

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
        normalSellerSloMs=1500,backlogLimit=settings.get('backlogLimit',200),retainedRowsLimit=2000,rawObservationLimitBytes=8388608,
        jarSha256=hashlib.sha256((REPO/'partner-integration-service/build/libs/app.jar').read_bytes()).hexdigest(),
        jarSha256ByModule={module:hashlib.sha256((REPO/module/'build/libs/app.jar').read_bytes()).hexdigest() for module in ['order-service','inventory-service','partner-integration-service']},
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

MARKET_SELLERS=20
MARKET_ORDERS=300

def seller_for(i, workload):
    if workload=='pair':return 'slow' if i%2==0 else 'normal'
    # One large seller with 20% of orders turns slow; 19 others share the rest.
    return 'slow' if i%5==0 else f's{i%(MARKET_SELLERS-1)+1:02d}'

def dataset(run_id, orders=24, operations=3, workload='pair'):
    values=[]
    # Each phase is interleaved between sellers.
    for seq in range(1,operations+1):
        for i in range(orders):
            # runId is limited to 64 characters by the event contract; the tail keeps the unique suffix.
            values.append(dict(eventId=str(uuid.uuid5(uuid.NAMESPACE_URL,f'{run_id}/{i}/{seq}')),runId=run_id[-64:],
                sellerId=seller_for(i,workload),orderId=i+1,sequence=seq,
                operation=['CREATE','CHANGE','CANCEL'][seq-1],occurredAt=now(),schemaVersion=1,
                quantity=seq+1,price=float(100+seq)))
    return values

def snapshot(root):
    remote=http(PARTNER_API+'/snapshot')
    effects=sql('SELECT * FROM partner_effect ORDER BY completed_at,event_id')
    orders=sql('SELECT * FROM partner_order ORDER BY seller_id,order_id')
    save(root/'remote.json',remote);save(root/'db-effects.json',effects);save(root/'db-orders.json',orders)
    save(root/'inbox.json',sql('SELECT event_id,seller_id,order_id,seq,state,attempts,received_at,done_at FROM inbox'))
    save(root/'db-io-after.json',sql("SELECT OBJECT_NAME,COUNT_READ,COUNT_WRITE,COUNT_FETCH,COUNT_INSERT,COUNT_UPDATE,COUNT_DELETE FROM performance_schema.table_io_waits_summary_by_table WHERE OBJECT_SCHEMA='partner_db'"))
    sizes=sql("SELECT TABLE_NAME,DATA_LENGTH,INDEX_LENGTH,TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_SCHEMA='partner_db'")
    save(root/'storage.json',sizes)
    save(root/'storage-live.json',sql("SELECT (SELECT COUNT(*) FROM inbox) AS inbox_rows,(SELECT COALESCE(SUM(OCTET_LENGTH(payload)),0) FROM inbox) AS inbox_payload_bytes,(SELECT COUNT(*) FROM partner_effect) AS effect_rows,(SELECT COUNT(*) FROM partner_order) AS order_rows"))
    save(root/'final-observe.json',http(SERVICE_URL+'/observe'))
    if not topology.DISTRIBUTED:(root/'infra-resources.jsonl').write_text(command(COMPOSE+['stats','--no-stream','--format','json']))
    return remote,effects,orders

# Com_* counters are not exposed in performance_schema.global_status; statement counts come from the digest summary.
MYSQL_STATUS=['Questions','Threads_connected','Innodb_os_log_fsyncs',
              'Innodb_data_fsyncs','Innodb_os_log_written','Innodb_data_written','Innodb_row_lock_waits','Innodb_row_lock_time']

def mysql_counters():
    """Server-wide counters; the partner schema is the only active one during these runs."""
    status={r['VARIABLE_NAME']:int(r['VARIABLE_VALUE']) for r in sql("SELECT VARIABLE_NAME,VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME IN ("+','.join(f"'{x}'" for x in MYSQL_STATUS)+")")}
    statements={r['EVENT_NAME'].split('/')[-1]:(int(r['COUNT_STAR']),int(r['SUM_TIMER_WAIT'])) for r in sql("SELECT EVENT_NAME,COUNT_STAR,SUM_TIMER_WAIT FROM performance_schema.events_statements_summary_global_by_event_name WHERE EVENT_NAME IN ('statement/sql/insert','statement/sql/update','statement/sql/delete','statement/sql/select','statement/sql/commit')")}
    # MISC on the redo log file is dominated by fsync.
    files={r['EVENT_NAME'].split('/')[-1]:dict(writes=int(r['COUNT_WRITE']),writePs=int(r['SUM_TIMER_WRITE']),misc=int(r['COUNT_MISC']),miscPs=int(r['SUM_TIMER_MISC'])) for r in sql("SELECT EVENT_NAME,COUNT_WRITE,SUM_TIMER_WRITE,COUNT_MISC,SUM_TIMER_MISC FROM performance_schema.file_summary_by_event_name WHERE EVENT_NAME IN ('wait/io/file/innodb/innodb_log_file','wait/io/file/innodb/innodb_data_file')")}
    return dict(time=now(),status=status,statements=statements,files=files)

def mysql_usage(before, after):
    seconds=(after['time']-before['time'])/1000
    delta=lambda key:after['status'].get(key,0)-before['status'].get(key,0)
    commits=after['statements'].get('commit',(0,0))[0]-before['statements'].get('commit',(0,0))[0]
    result=dict(seconds=round(seconds,2),commitsPerSecond=round(commits/seconds,1),questionsPerSecond=round(delta('Questions')/seconds,1),
                redoFsyncs=delta('Innodb_os_log_fsyncs'),redoMBWritten=round(delta('Innodb_os_log_written')/1048576,2),
                rowLockWaits=delta('Innodb_row_lock_waits'),rowLockTimeMs=delta('Innodb_row_lock_time'),threadsConnected=after['status'].get('Threads_connected'))
    for name,(count,ps) in after['statements'].items():
        c=count-before['statements'].get(name,(0,0))[0];t=ps-before['statements'].get(name,(0,0))[1]
        result[f'{name}Count']=c;result[f'{name}AvgUs']=round(t/c/1e6,1) if c else None
    log=after['files'].get('innodb_log_file');log0=before['files'].get('innodb_log_file')
    if log and log0:
        m=log['misc']-log0['misc'];result['redoMiscAvgUs']=round((log['miscPs']-log0['miscPs'])/m/1e6,1) if m else None
        w=log['writes']-log0['writes'];result['redoWriteAvgUs']=round((log['writePs']-log0['writePs'])/w/1e6,1) if w else None
    return result

def percentile(values, quantile):
    return sorted(values)[max(0,math.ceil(quantile*len(values))-1)] if values else None

def report(root, expected, result):
    remote=json.loads((root/'remote.json').read_text())
    rows=[]
    for seller in ['normal','slow']:
        latencies=[x['effect_at']-x['occurred_at'] for x in remote['effects'] if (x['seller_id']=='slow')==(seller=='slow')]
        rows.append(dict(seller=seller,p50Ms=percentile(latencies,.5),p95Ms=percentile(latencies,.95),p99Ms=percentile(latencies,.99),maxMs=max(latencies,default=0),samples=len(latencies)))
    observations=[json.loads(x) for x in (root/'observations.jsonl').read_text().splitlines()] if (root/'observations.jsonl').exists() else []
    traces=[]
    for line in (root/'partner-integration-service.log').read_text().splitlines():
        if line.startswith('TRACE '):traces.append(json.loads(line[6:]))
    save(root/'traces.json',traces)
    controller=json.loads((root/'controller.json').read_text())
    removed=next((x['time'] for x in controller if x['action']=='fault_removed'),None)
    input_done=next((x['time'] for x in controller if x['action']=='workload_input_done'),None)
    external_slow_done=max((x['effect_at'] for x in remote['effects'] if x['seller_id']=='slow'),default=0)
    slow_done=max((x['time'] for x in traces if x['stage']=='business_commit' and x.get('sellerId')=='slow'),default=external_slow_done)
    dbwait=[x.get('waitMs',0) for x in traces if x['stage']=='db_acquired']
    external=[x.get('durationMs',0) for x in traces if x['stage']=='external_result']
    errors=[x for x in traces if x['stage']=='external_error']
    service_samples=[x['service'] for x in observations if x.get('service')]
    metrics=dict(latency=rows,externalRecoveryMs=max(0,external_slow_done-removed) if removed else None,recoveryMs=max(0,slow_done-removed) if removed else None,
        oldestBySeller=dict(normal=max((v for x in observations for k,v in x.get('oldestMs',{}).items() if k!='slow'),default=0),
                            slow=max((x.get('oldestMs',{}).get('slow',0) for x in observations),default=0)),
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
    metrics['externalSqliteBytes']=None if REMOTE_PARTNER else (root/'external.sqlite').stat().st_size + sum(x.stat().st_size for x in root.glob('external.sqlite-*'))
    metrics['rawEvidenceBytes']=sum(x.stat().st_size for x in root.rglob('*') if x.is_file())
    final=json.loads((root/'final-observe.json').read_text())
    metrics['retryTopicRecords']=final.get('retryLogEnd')
    metrics['parkedEvents']=sum(1 for x in traces if x['stage']=='retry_parked')
    metrics['circuitOpenRejections']=sum(1 for x in traces if x['stage']=='circuit_open')
    metrics['externalRequests']=len(remote['attempts'])
    # Market input keeps arriving after the fault is removed; measure the tail after the last input too.
    metrics['slowDrainAfterInputMs']=max(0,external_slow_done-input_done) if input_done else None
    started=next((x['time'] for x in controller if x['action']=='workload_started'),None)
    occurred=[x['occurred_at'] for x in remote['effects']];done=[x['effect_at'] for x in remote['effects']]
    metrics['inputEventsPerSecond']=round(len(expected)/((input_done-started)/1000),1) if input_done and started and input_done>started else None
    metrics['deliveredEventsPerSecond']=round(len(done)/((max(done)-min(occurred))/1000),1) if done and max(done)>min(occurred) else None
    metrics['peakCommittedRemaining']=max((x.get('committedRemaining') or 0 for x in service_samples),default=None)
    usage=next((x for x in controller if x['action']=='resource_usage'),None)
    metrics['hosts']=usage['hosts'] if usage else None
    metrics['mysql']=usage['mysql'] if usage else None
    before_observe=json.loads((root/'before-load-observe.json').read_text()) if (root/'before-load-observe.json').exists() else {}
    final_observe=json.loads((root/'final-observe.json').read_text())
    metrics['gcCount']=final_observe.get('gcCount',0)-before_observe.get('gcCount',0) if 'gcCount' in final_observe else None
    metrics['gcMillis']=final_observe.get('gcMillis',0)-before_observe.get('gcMillis',0) if 'gcMillis' in final_observe else None
    produce=[(x.get('producer') or {}).get('request-latency-avg') for x in service_samples]
    produce=[x for x in produce if isinstance(x,(int,float)) and x==x]
    metrics['producerRequestLatencyAvgMsPeak']=round(max(produce),2) if produce else None
    metrics['peakDbConnectionsWaiting']=max((x.get('dbWaiting',0) for x in service_samples),default=None)
    if (root/'input-times.json').exists():
        inputs=json.loads((root/'input-times.json').read_text())
        source_rows=[]
        for seller in ['normal','slow']:
            times=[row['effect_at']-inputs[f'{row["order_id"]}/{row["seq"]}'] for row in remote['effects'] if (row['seller_id']=='slow')==(seller=='slow')]
            source_rows.append(dict(seller=seller,p95Ms=percentile(times,.95),p99Ms=percentile(times,.99)))
        metrics['sourceApiLatency']=source_rows
    save(root/'summary.json',metrics)
    return metrics

def run_case(base, mode, scenario, repeat, observe=True, workload='pair', partitions=1, rate=80, duration=12):
    label=f'{mode}-{scenario}' if workload=='pair' else f'{mode}-{scenario}-{workload}-p{partitions}'+(f'-{rate}eps' if workload=='sweep' else '')
    root=new_root(base,f'{label}-r{repeat}')
    topic='partner-'+uuid.uuid4().hex[:16]; group=topic+'-group'
    orders={'pair':24,'market':MARKET_ORDERS,'sweep':rate*duration//3}[workload]
    # pair/market keep the original 8 events per 100ms; sweep sets the arrival rate explicitly.
    per_tick=8 if workload!='sweep' else max(1,rate//10)
    settings=dict(mode=mode,scenario=scenario,repeat=repeat,topic=topic,group=group,observe=observe,backlogLimit=8 if scenario=='backlog' else 200,
                  workload=workload,partitions=partitions,orders=orders,sellers=2 if workload=='pair' else MARKET_SELLERS,
                  targetEventsPerSecond=per_tick*10,distributed=topology.DISTRIBUTED,replicationFactor=topology.REPLICATION_FACTOR)
    metadata(root,settings)
    sql('DELETE FROM inbox; DELETE FROM partner_effect; DELETE FROM partner_order') if sql('SHOW TABLES') else None
    if REMOTE_PARTNER:
        http(PARTNER_API+'/reset',dict(name=root.name));mock=None
    else:mock=launch([sys.executable,str(REPO/'experiments/mock-partner-api/server.py'),'--database',str(root/'external.sqlite')],root/'mock.log')
    extra=dict(PARTNER_MODE=mode,PARTNER_TOPIC=topic,PARTNER_GROUP=group,APP_PARTITIONS=partitions,APP_PARTNER_URL=PARTNER_API)
    # inbox-batch is the inbox mode with one transaction per poll (inbox v2).
    if mode=='inbox-batch':extra.update(PARTNER_MODE='inbox',APP_INBOX_BATCH_INGEST='true')
    if workload=='market':extra['APP_INPUT_BUDGET']=5000
    if workload=='sweep':
        # Long, fast inputs: keep completed inbox rows only briefly so the retained-row bound is not the limit.
        extra.update(APP_INPUT_BUDGET=0,APP_RETAINED_LIMIT=20000,APP_INBOX_DONE_RETENTION_MS=2000)
    if scenario=='rebalance':extra['SPRING_KAFKA_CONSUMER_PROPERTIES_MAX_POLL_INTERVAL_MS']=2000
    if scenario=='backlog':extra['APP_BACKLOG_LIMIT']=8
    if not observe:extra['APP_TRACE_ENABLED']='false'
    service=None;collector=None; control=[]; threads=[]; expected=[]
    def record(action, **detail):
        control.append(dict(time=now(),action=action,**detail));save(root/'controller.json',control)
    fault_seconds=12 if scenario=='hang' else 6
    def clear_fault():
        time.sleep(fault_seconds)
        http(PARTNER_API+'/control',dict(sellerId='slow',delayMs=0,failureCount=0))
        record('fault_removed')
    try:
        wait_for(lambda:http(PARTNER_API+'/health'),label='mock ready')
        service=java('partner-integration-service',root,extra)
        wait_for(lambda:http(SERVICE_URL+'/observe').get('assigned',0)>=(1 if mode in ('inbox','inbox-batch') else partitions),label='partner assignment ready')
        http(SERVICE_URL+'/load',[])
        # Allow assignment and capture 1s of fault-free data before injection.
        if observe:
            collector=launch([sys.executable,str(REPO/'experiments/collector.py'),'--directory',str(root),'--pids','' if topology.PARTNER_HOST else (f'{service.pid},{mock.pid}' if mock else str(service.pid))],root/'collector.log')
        time.sleep(1)
        save(root/'before-load-observe.json',http(SERVICE_URL+'/observe'))
        save(root/'db-io-before.json',sql("SELECT OBJECT_NAME,COUNT_READ,COUNT_WRITE,COUNT_FETCH,COUNT_INSERT,COUNT_UPDATE,COUNT_DELETE FROM performance_schema.table_io_waits_summary_by_table WHERE OBJECT_SCHEMA='partner_db'"))
        hosts_before=topology.sample_hosts();mysql_before=mysql_counters()
        record('workload_started')
        expected=dataset(root.name,orders,3,workload)
        if scenario=='hotkey':
            for event in expected:event['sellerId']='slow' if event['orderId']==1 else 'normal'
        save(root/'expected.json',[])
        if scenario=='broker-failover':
            # One of three brokers dies mid-input and returns 10s later (needs RF=3, min ISR 2).
            if len(topology.KAFKA_HOSTS)<3:raise AssertionError('broker-failover needs three brokers')
            def fail_broker():
                time.sleep(3);record('broker_killed',host=topology.KAFKA_HOSTS[1])
                topology.ssh(topology.KAFKA_HOSTS[1],'docker kill kafka')
                time.sleep(10);topology.ssh(topology.KAFKA_HOSTS[1],'docker start kafka');record('broker_restarted')
            thread=threading.Thread(target=fail_broker);thread.start();threads.append(thread)
        if scenario in {'api','backlog','broker-kill','hotkey','park-kill','hang'}:
            # hang: the API answers just inside the 5s client timeout, as a stuck dependency does.
            delay=3000 if scenario=='hang' else 600
            http(PARTNER_API+'/control',dict(sellerId='slow',delayMs=delay,failureCount=1000 if scenario=='hotkey' else 0))
            record('fault_injected',kind='seller_api_delay',delayMs=delay,durationMs=fault_seconds*1000)
            thread=threading.Thread(target=clear_fault);thread.start();threads.append(thread)
        elif scenario=='retry':
            http(PARTNER_API+'/control',dict(sellerId='slow',failureCount=3))
            record('fault_injected',kind='seller_error',failureCount=3)
            thread=threading.Thread(target=clear_fault);thread.start();threads.append(thread)
        elif scenario=='response-loss':
            http(PARTNER_API+'/control',dict(sellerId='slow',dropResponse=True))
            record('fault_injected',kind='response_loss')
        elif scenario=='db':
            http(SERVICE_URL+'/fault/db',dict(durationMs=3000))
            record('fault_injected',kind='shared_db_pool',durationMs=3000)
        gate=None
        if scenario in {'ack-kill','ack-release','inbox-kill','worker-kill','external-kill','business-before-kill','inbox-before-kill','rebalance','park-kill'}:
            gate={'inbox-kill':'inbox_commit','worker-kill':'worker_commit','external-kill':'external_success','business-before-kill':'business_before_commit','inbox-before-kill':'inbox_before_commit','park-kill':'retry_published'}.get(scenario,'business_commit')
            (root/'hooks'/f'{gate}.arm').touch()
            record('gate_armed',gate=gate)
        placements=[]
        # All modes get identical event count, interleaving and arrival cadence (one batch per 100ms).
        tick=time.monotonic();last_saved=0
        for i in range(0,len(expected),per_tick):
            for event in expected[i:i+per_tick]:event['occurredAt']=now()
            if workload!='sweep' or time.monotonic()-last_saved>=1:
                save(root/'expected.json',expected[:i+per_tick]);last_saved=time.monotonic()
            placements.extend(http(SERVICE_URL+'/load',expected[i:i+per_tick],timeout=90))
            tick+=0.1
            time.sleep(max(0,tick-time.monotonic()))
        save(root/'expected.json',expected)
        save(root/'placements.json',placements)
        record('workload_input_done',events=len(expected))
        if partitions==1 and {x['partition'] for x in placements}!={0}:raise AssertionError('Sellers did not share partition 0')
        if partitions>1:
            # The order key spreads the slow seller over every partition; record that, do not assume it.
            slow_parts={x['partition'] for x in placements if x['sellerId']=='slow'}
            if slow_parts!=set(range(partitions)):raise AssertionError(f'Slow seller not in every partition: {slow_parts}')
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
            save(root/'at-boundary-observe.json',http(SERVICE_URL+'/observe'))
            save(root/'at-boundary-effects.json',sql('SELECT * FROM partner_effect'))
            save(root/'at-boundary-inbox.json',sql('SELECT event_id,state,kafka_offset FROM inbox'))
            record('gate_observed',gate=gate,eventId=(root/'hooks'/f'{gate}.reached').read_text().splitlines()[0])
            if scenario.endswith('kill'):
                stop(service,kill=True);record('process_killed',gate=gate)
                (root/'hooks'/f'{gate}.arm').unlink()
                service=java('partner-integration-service',root,extra)
                wait_for(lambda:http(SERVICE_URL+'/observe'),label='restarted service')
                record('process_restarted')
            else:
                (root/'hooks'/f'{gate}.arm').unlink();record('gate_released')
        if scenario=='redelivery':
            http(SERVICE_URL+'/load',expected)
            record('duplicate_input')
        wait_for(lambda:sql('SELECT COUNT(*) AS n FROM partner_effect')[0]['n']==str(len(expected)),timeout=600 if workload=='sweep' else 180,label='all business effects')
        mysql_after=mysql_counters();hosts_after=topology.sample_hosts()
        save(root/'mysql-counters.json',dict(before=mysql_before,after=mysql_after))
        record('resource_usage',hosts=topology.host_usage(hosts_before,hosts_after,(mysql_after['time']-mysql_before['time'])/1000),mysql=mysql_usage(mysql_before,mysql_after))
        for thread in threads:thread.join()
        wait_for(lambda:http(SERVICE_URL+'/observe').get('committedRemaining')==0,timeout=30,label='broker commit drained')
        time.sleep(.5)
        remote,effects,orders=snapshot(root)
        result=check(expected,remote,effects,orders);save(root/'checker.json',result)
        if collector:
            (root/'collector.stop').touch();collector.wait(timeout=8)
        if isinstance(service,RemoteJvm):service.fetch_log()
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

NEW_MODES=['circuit-breaker','retry-topic','parallel-consumer']

def comparison_suite(evidence):
    # Pair workload keeps continuity with the first comparison.
    for repeat in range(1,4):
        for mode in NEW_MODES:run_case(evidence,mode,'api',repeat)
    # 20 sellers: does adding partitions, a retry topic or a key-parallel library protect them?
    for repeat in range(1,4):
        for partitions in [1,4]:
            for mode in MODES:run_case(evidence,mode,'api',repeat,workload='market',partitions=partitions)
    for repeat in range(1,4):
        for mode in ['sequential','retry-topic','parallel-consumer','inbox']:
            run_case(evidence,mode,'hang',repeat,workload='market',partitions=4)
    for mode in NEW_MODES:
        for scenario in ['db','response-loss','redelivery','retry','hotkey','broker-kill','external-kill','business-before-kill','ack-kill']:
            run_case(evidence,mode,scenario,1)
    run_case(evidence,'retry-topic','park-kill',1)

if __name__ == '__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--evidence',default=str(DEFAULT_EVIDENCE))
    p.add_argument('--baseline',action='store_true')
    p.add_argument('--mode',choices=MODES)
    p.add_argument('--workload',default='pair',choices=['pair','market','sweep'])
    p.add_argument('--rate',type=int,default=80,help='sweep: events per second')
    p.add_argument('--duration',type=int,default=12,help='sweep: seconds of input')
    p.add_argument('--partitions',type=int,default=1)
    p.add_argument('--scenario',default='api',choices=['clean','api','db','ack-kill','ack-release','inbox-kill','worker-kill','external-kill','response-loss','redelivery','retry','backlog','business-before-kill','inbox-before-kill','rebalance','broker-kill','hotkey','park-kill','hang','broker-failover'])
    p.add_argument('--repeat',type=int,default=1)
    p.add_argument('--suite',action='store_true')
    p.add_argument('--no-observe',action='store_true')
    p.add_argument('--comparison',action='store_true',help='standard remedies under the 20-seller workload')
    args=p.parse_args()
    evidence=Path(args.evidence).expanduser().resolve()
    validate_evidence_path(evidence)
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
            for repeat in range(1,4):
                for mode in ['sequential','async','inbox']:
                    run_case(evidence,mode,'clean',repeat)
                    run_case(evidence,mode,'clean',repeat,False)
            comparison_suite(evidence)
        elif args.comparison:comparison_suite(evidence)
        else:run_case(evidence,args.mode or 'sequential',args.scenario,args.repeat,not args.no_observe,args.workload,args.partitions,args.rate,args.duration)
    finally:
        for process in PROCESSES:stop(process)
        for handle in HANDLES:handle.close()
