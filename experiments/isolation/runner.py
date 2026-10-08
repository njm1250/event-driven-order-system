#!/usr/bin/env python3
"""Runs one experiment run end to end on the cluster (or locally) and checks it.

A run: fresh topics, databases and seller ledger; source and partner JVMs started with the
comparison contract; a planned trace sent from the producer host through the source; the fault and
scenario steps at fixed offsets; a bounded drain; then everything is collected into the run
directory and checked. Each run gets its own topic and group names, so a process left from an
earlier run cannot write into this one.
"""
import json
import random
import re
import subprocess
import sys
import threading
import time
import urllib.request
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check  # noqa: E402
import trace as tracegen  # noqa: E402
import topology as topo  # noqa: E402

CANDIDATES = {
    'kafka-bucket': dict(mode='kafka-bucket', routing='bucket'),
    'kafka-retry': dict(mode='kafka-retry', routing='order'),
    'db-inbox': dict(mode='inbox', routing='order'),
}
JVM = ['java', '-Xms512m', '-Xmx512m', '-XX:ActiveProcessorCount=2']
NORMAL_DELAY_MS = 20
FAULT_DELAY_MS = 3000
TRACE_STAGES = ('claimed,business_commit,stale_owner_rejected,lease_lost,retry_budget_wait,partitions_assigned,'
                'partitions_revoked,worker_drain,backpressure,dispatch_error,claim_error,completion_relay_error,'
                'retry_save_error,lease_renew_error,inbox_purge_error')
PARTNER_JAR = 'partner-integration-service/build/libs/app.jar'
SOURCE_JAR = 'order-service/build/libs/app.jar'


def now_ms():
    return int(time.time() * 1000)


def http(url, data=None, timeout=10):
    body = None if data is None else json.dumps(data).encode()
    request = urllib.request.Request(url, data=body, headers={'Content-Type': 'application/json'})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.load(response)


def wait_for(predicate, timeout, label):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        try:
            value = predicate()
            if value:
                return value
        except Exception as error:
            last = error
        time.sleep(0.25)
    raise TimeoutError(f'{label} (last: {last})')


class Run:
    def __init__(self, base, spec):
        self.spec = spec
        self.tag = uuid.uuid4().hex[:8]
        self.root = Path(base)/f"{time.strftime('%Y%m%d-%H%M%S')}-{spec['label']}-{self.tag[:5]}"
        self.root.mkdir(parents=True)
        self.events = []
        self.partner_pids = {}
        self.topic = f'p-{self.tag}'
        self.completion_topic = f'c-{self.tag}'
        self.group = f'g-{self.tag}'
        self.threads = []
        self.info = dict(spec=spec, topic=self.topic, completionTopic=self.completion_topic, group=self.group,
                         faultSeller=tracegen.BIG_SELLER, judgedPhases=spec.get('judgedPhases', []))

    def record(self, action, **detail):
        self.events.append(dict(time=now_ms(), action=action, **detail))
        (self.root/'timeline.json').write_text(json.dumps(self.events, indent=1))

    # ---------- environment
    def stop_all(self):
        for host in topo.PARTNERS:
            topo.run(host, f"pkill -KILL -f '{PARTNER_JAR[:-1]}[{PARTNER_JAR[-1]}]' || true", check=False)
        topo.run(topo.SOURCE, f"pkill -KILL -f '{SOURCE_JAR[:-1]}[{SOURCE_JAR[-1]}]' || true", check=False)

    def reset(self):
        self.stop_all()
        topo.mysql(topo.PARTNER_DB, 'partner_db', 'TRUNCATE inbox; TRUNCATE partner_effect; TRUNCATE partner_order; '
                   'TRUNCATE seller_permit; TRUNCATE retry_admission; TRUNCATE completion_outbox')
        topo.mysql(topo.SOURCE_DB, 'order_db', 'TRUNCATE delivery_obligation; TRUNCATE outbox_event')
        for topic, partitions in [(self.topic, 4), (self.topic + '-retry', 4), (self.completion_topic, 4)]:
            topo.kafka_topics(['--create', '--if-not-exists', '--topic', topic, '--partitions', str(partitions),
                               '--replication-factor', str(topo.REPLICATION_FACTOR),
                               '--config', f'min.insync.replicas={topo.MIN_ISR}', '--config', 'retention.ms=86400000',
                               '--config', 'retention.bytes=2147483648'])
        http(topo.MOCK.url() + '/reset', dict(name=self.root.name))
        http(topo.MOCK.url() + '/control', {'sellerId': '*', 'delayMs': NORMAL_DELAY_MS})
        self.record('reset_done')

    def start_source(self):
        host = topo.SOURCE
        db = f'jdbc:mysql://{topo.SOURCE_DB.address}:{topo.SOURCE_DB.port}/order_db'
        args = [f'--server.port={host.port}', f'--spring.datasource.url={db}', f'--spring.kafka.bootstrap-servers={topo.KAFKA_BOOTSTRAP}',
                '--app.delivery-tracking=true', f'--app.partner-topic={self.topic}', f'--app.completion-topic={self.completion_topic}',
                f'--app.completion-group=s-{self.tag}', '--app.outbox-poll-ms=10', '--app.outbox-retention-ms=60000',
                '--spring.kafka.producer.properties[enable.idempotence]=true', '--spring.kafka.producer.acks=all',
                '--spring.kafka.consumer.auto-offset-reset=earliest', '--logging.level.org.apache.kafka=WARN']
        self._launch(host, SOURCE_JAR, args, f'source-{self.root.name}.log', {})
        wait_for(lambda: http(host.url() + '/experiment/observe').get('time'), 90, 'source ready')
        self.record('source_ready')

    def start_partner(self, index):
        host = topo.PARTNERS[index]
        spec = self.spec
        db = f'jdbc:mysql://{topo.PARTNER_DB.address}:{topo.PARTNER_DB.port}/partner_db'
        args = [f'--server.port={host.port}', f'--spring.datasource.url={db}', f'--spring.kafka.bootstrap-servers={topo.KAFKA_BOOTSTRAP}',
                f'--app.mode={CANDIDATES[spec["candidate"]]["mode"]}', f'--app.topic={self.topic}',
                f'--spring.kafka.consumer.group-id={self.group}', '--app.partitions=4',
                f'--app.replication-factor={topo.REPLICATION_FACTOR}', f'--app.partner-url={topo.MOCK.url()}',
                f'--app.completion-topic={self.completion_topic}', f'--app.backlog-limit={spec.get("backlogLimit", 200)}',
                '--app.retained-limit=100000', '--app.inbox-done-retention-ms=60000', '--app.input-budget=0',
                '--app.inbox-batch-ingest=true', f'--app.trace-stages={TRACE_STAGES}', f'--app.retry.seed={spec["seed"]}',
                # Parallel Consumer may hold workers x 50 = 200 records, the same as the inbox's pending limit.
                f'--app.parallel-load-factor={spec.get("parallelLoadFactor", 50)}',
                '--spring.kafka.consumer.max-poll-records=32',
                '--spring.kafka.consumer.properties[session.timeout.ms]=15000',
                '--spring.kafka.consumer.properties[heartbeat.interval.ms]=3000',
                '--spring.kafka.consumer.properties[max.partition.fetch.bytes]=262144',
                '--spring.kafka.consumer.properties[fetch.max.bytes]=1048576',
                '--spring.kafka.consumer.properties[interceptor.classes]=']
        env = {'EXPERIMENT_HOOK_DIR': f'{host.workdir}/hooks'}
        topo.run(host, 'mkdir -p hooks && rm -f hooks/*')
        pid = self._launch(host, PARTNER_JAR, args, f'partner-{self.root.name}.log', env)
        self.partner_pids[host.name] = pid
        self.record('partner_started', host=host.name, pid=pid)
        return host

    def _launch(self, host, jar, args, log, env):
        quoted = ' '.join("'" + a.replace("'", "'\\''") + "'" for a in args)
        assignments = ' '.join(f"{k}='{v}'" for k, v in env.items())
        jvm = JVM + [f'-D{k}={v}' for k, v in self.spec.get('jvmProperties', {}).items()] + ['-jar']
        command = (f"mkdir -p logs; cd {host.repo}; nohup env {assignments} {' '.join(jvm)} {jar} {quoted} "
                   f">> {host.workdir}/logs/{log} 2>&1 < /dev/null & echo $!")
        return int(topo.run(host, command).strip().splitlines()[-1])

    def assigned(self, hosts):
        return sum(http(h.url() + '/observe', timeout=5).get('assigned') or 0 for h in hosts)

    def clock_offsets(self):
        offsets = {}

        def probe(host):
            try:
                out = topo.run(host, 'chronyc tracking', timeout=15)
                m = re.search(r'System time\s*:\s*([0-9.]+) seconds (fast|slow)', out)
                offsets[host.name] = round(float(m.group(1)) * 1000 * (1 if m.group(2) == 'fast' else -1), 3) if m else None
            except Exception:
                offsets[host.name] = None
        threads = [threading.Thread(target=probe, args=(h,)) for h in topo.CLOCK_HOSTS]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        return offsets

    # ---------- run
    def execute(self):
        spec = self.spec
        phases = spec['phases']
        bounds = tracegen.phase_bounds(phases)
        self.info['phaseBounds'] = bounds
        entries = tracegen.build(spec['seed'], phases, run_id=self.root.name)
        tracegen.write(self.root/'trace.jsonl', entries, CANDIDATES[spec['candidate']]['routing'])
        self.info['plannedEvents'] = len(entries)
        partners_at_start = spec.get('partnersAtStart', 1)
        instances = max(partners_at_start, 2 if spec.get('scenario') == 'scale' else 1)
        self.info['limits'] = dict(globalPerInstance=4, **{'global': 4 * instances}, seller=2, retryPerSecond=2, instances=instances)
        observer = None
        load = None
        try:
            self.reset()
            topo.copy_to(topo.PRODUCER, self.root/'trace.jsonl', 'trace.jsonl')
            topo.copy_to(topo.PRODUCER, Path(__file__).parent/'load.py', 'load.py')
            self.start_source()
            started = [self.start_partner(i) for i in range(partners_at_start)]
            wait_for(lambda: self.assigned(started) >= 4, 120, 'partitions assigned')
            self.record('partners_ready')
            self.info['clockOffsetsMs'] = self.clock_offsets()
            observer = subprocess.Popen([sys.executable, str(Path(__file__).parent/'observer.py'), '--out', str(self.root/'metrics.jsonl'),
                                         '--source', topo.SOURCE.url(), '--partners', ','.join(h.url() for h in topo.PARTNERS[:instances]),
                                         '--stop-file', str(self.root/'observer.stop'), '--pause-file', str(self.root/'observer.pause')],
                                        stdout=subprocess.DEVNULL, stderr=open(self.root/'observer.err', 'w'))
            start_at = now_ms() + 3000
            self.info['startAt'] = start_at
            load = subprocess.Popen(topo.ssh_args(topo.PRODUCER) + [
                f'cd {topo.PRODUCER.workdir} && python3 load.py --trace trace.jsonl --target source --url {topo.SOURCE.url()} '
                f'--start-at {start_at} --out load.jsonl'] if not topo.LOCAL else
                ['bash', '-c', f'cd {topo.PRODUCER.workdir} && {sys.executable} load.py --trace trace.jsonl --target source '
                 f'--url {topo.SOURCE.url()} --start-at {start_at} --out load.jsonl'],
                stdout=open(self.root/'load-summary.json', 'w'), stderr=open(self.root/'load.err', 'w'))
            self.record('load_started', startAt=start_at)
            if spec.get('fault'):
                begin, end = bounds[spec['fault']]
                self.info['fault'] = dict(phase=spec['fault'], **{'from': start_at + begin, 'to': start_at + end})
                self._at(start_at + begin, lambda: self._fault(FAULT_DELAY_MS, 'fault_injected'))
                self._at(start_at + end, lambda: self._fault(NORMAL_DELAY_MS, 'fault_removed'))
            scenario = spec.get('scenario')
            if scenario:
                begin, _ = bounds[spec['scenarioPhase']]
                if scenario == 'scale':
                    self._at(start_at + begin + 30_000, self._scale_out)
                elif scenario == 'stale':
                    self._at(start_at + begin, lambda: self._stale_owner(entries, start_at, begin))
                elif scenario == 'observer-pause':
                    self._at(start_at + begin + 30_000, self._pause_source_signal)
                elif scenario == 'observer-off':
                    for name, off in spec['observerWindows']:
                        b, e = bounds[name]
                        if off:
                            self._at(start_at + b, lambda: (self.root/'observer.pause').touch() or self.record('observer_off'))
                            self._at(start_at + e, lambda: (self.root/'observer.pause').unlink(missing_ok=True) or self.record('observer_on'))
            load.wait(timeout=bounds[phases[-1][0]][1] / 1000 + 300)
            self.record('load_done')
            for thread in self.threads:
                thread.join(timeout=120)
            self._drain(spec.get('drainSeconds', 60))
        finally:
            if observer:
                (self.root/'observer.stop').touch()
                try:
                    observer.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    observer.kill()
            if load and load.poll() is None:
                load.kill()
            try:
                self.collect()
            finally:
                self.stop_all()
        result = check.check(self.root)
        (self.root/'checks.json').write_text(json.dumps(result, indent=2))
        return result

    def _at(self, epoch_ms, action):
        def runner():
            time.sleep(max(0, (epoch_ms - now_ms()) / 1000))
            try:
                action()
            except Exception as error:
                self.record('step_failed', error=repr(error))
        thread = threading.Thread(target=runner, daemon=True)
        thread.start()
        self.threads.append(thread)

    def _fault(self, delay, action):
        http(topo.MOCK.url() + '/control', {'sellerId': tracegen.BIG_SELLER, 'delayMs': delay})
        self.record(action, seller=tracegen.BIG_SELLER, delayMs=delay)

    def _scale_out(self):
        self.record('second_instance_starting')
        host = self.start_partner(1)
        wait_for(lambda: http(host.url() + '/observe', timeout=5).get('assigned', 0) > 0, 120, 'second instance assigned')
        self.record('second_instance_assigned')

    def _pause_source_signal(self):
        http(topo.SOURCE.url() + '/experiment/observe/pause', dict(durationMs=20_000))
        self.record('source_signal_paused', durationMs=20_000)

    def _stale_owner(self, entries, start_at, begin):
        """Holds one chosen operation after its seller call succeeded, stops that JVM, waits for the
        other instance to take the work over, then lets the stopped one continue."""
        target = next(e for e in entries if e['plannedOffsetMs'] >= begin + 5000
                      and e['event']['sellerId'] != tracegen.BIG_SELLER and e['event']['sequence'] == 1)
        event_id = target['event']['eventId']
        for host in topo.PARTNERS[:2]:
            topo.run(host, f"echo {event_id} > hooks/external_success.arm")
        self.record('gate_armed', eventId=event_id)

        def reached():
            for host in topo.PARTNERS[:2]:
                if topo.run(host, 'test -f hooks/external_success.reached && echo yes || true').strip() == 'yes':
                    return host
        try:
            held = wait_for(reached, 40, 'gate reached')
        except TimeoutError:
            self.record('injection_invalid', reason='gate not reached')
            for host in topo.PARTNERS[:2]:
                topo.run(host, 'rm -f hooks/external_success.arm', check=False)
            return
        other = next(h for h in topo.PARTNERS[:2] if h != held)
        topo.run(held, f'kill -STOP {self.partner_pids[held.name]}')
        stopped_at = now_ms()
        topo.run(other, 'rm -f hooks/external_success.arm')
        self.record('owner_stopped', host=held.name, eventId=event_id)
        try:
            wait_for(lambda: topo.mysql(topo.PARTNER_DB, 'partner_db',
                                        f"SELECT completed_at FROM partner_effect WHERE event_id='{event_id}'"), 60, 'takeover commit')
            self.record('takeover_committed', eventId=event_id, afterStopMs=now_ms() - stopped_at)
        except TimeoutError:
            self.record('takeover_unconfirmed', eventId=event_id)
        topo.run(held, 'rm -f hooks/external_success.arm')
        topo.run(held, f'kill -CONT {self.partner_pids[held.name]}')
        self.record('owner_resumed', host=held.name)
        self.info['staleOwner'] = dict(eventId=event_id, host=held.name, stoppedAt=stopped_at)

    def _drain(self, seconds):
        accepted = None
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                accepted = topo.mysql(topo.SOURCE_DB, 'order_db', 'SELECT COUNT(*) AS n FROM delivery_obligation')[0]['n']
                done = http(topo.MOCK.url() + '/stats')['effects']
                if int(accepted) and done >= int(accepted):
                    break
            except Exception:
                pass
            time.sleep(2)
        self.record('drain_done', accepted=accepted)

    def collect(self):
        self.info['collectedAt'] = now_ms()
        try:
            (self.root/'mock.json').write_text(json.dumps(http(topo.MOCK.url() + '/snapshot', timeout=120)))
        except Exception as error:
            self.record('collect_failed', what='mock', error=repr(error))
        try:
            rows = topo.mysql(topo.SOURCE_DB, 'order_db', 'SELECT event_id,seller_id,created_at,resolved_at,completed_at FROM delivery_obligation')
            for r in rows:
                for k in ('created_at', 'resolved_at', 'completed_at'):
                    r[k] = int(r[k]) if r[k] is not None else None
            (self.root/'obligations.json').write_text(json.dumps(rows))
        except Exception as error:
            self.record('collect_failed', what='obligations', error=repr(error))
        try:
            open_inbox = topo.mysql(topo.PARTNER_DB, 'partner_db', "SELECT event_id,seller_id,state,attempts,generation,owner FROM inbox WHERE state<>'DONE'")
            held = topo.mysql(topo.PARTNER_DB, 'partner_db', 'SELECT seller_id,slot,owner,event_id,generation,lease_until FROM seller_permit WHERE owner IS NOT NULL')
            effects = topo.mysql(topo.PARTNER_DB, 'partner_db', 'SELECT COUNT(*) AS n FROM partner_effect')[0]['n']
            (self.root/'partner-db.json').write_text(json.dumps(dict(openInbox=open_inbox, heldPermits=held, effects=int(effects))))
        except Exception as error:
            self.record('collect_failed', what='partner-db', error=repr(error))
        topo.copy_from(topo.PRODUCER, 'load.jsonl', self.root/'load.jsonl')
        for i, host in enumerate(topo.PARTNERS):
            if host.name in self.partner_pids:
                topo.copy_from(host, f'logs/partner-{self.root.name}.log', self.root/f'partner-{i + 1}.log')
        topo.copy_from(topo.SOURCE, f'logs/source-{self.root.name}.log', self.root/'source.log')
        self.info['events'] = self.events
        (self.root/'run.json').write_text(json.dumps(dict(self.info, startAt=self.info.get('startAt', 0)), indent=1))


def execute(base, spec):
    run = Run(base, spec)
    print(f"[{time.strftime('%H:%M:%S')}] start {run.root.name}", flush=True)
    began = time.monotonic()
    try:
        result = run.execute()
        summary = dict(run=run.root.name, spec=spec, seconds=round(time.monotonic() - began),
                       safety=result['safety']['passed'], liveness=result['liveness']['passed'],
                       budget=result['budget']['passed'], latency=result['latencyPassed'],
                       evidence=result['evidence']['passed'])
    except Exception as error:
        summary = dict(run=run.root.name, spec=spec, seconds=round(time.monotonic() - began), error=repr(error))
    (run.root/'summary.json').write_text(json.dumps(summary, indent=1))
    print(json.dumps(summary), flush=True)
    return run.root, summary
