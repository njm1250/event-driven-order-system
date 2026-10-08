#!/usr/bin/env python3
"""The experiment plan (design v2.1): G0 -> C0 -> E1 -> E2 -> E3 -> E4 inside a fixed time budget.

Every run is planned before it starts (plan.jsonl), runs in a shuffled order per seed block, and is
kept whatever its outcome. A run is not started when the remaining budget cannot cover it, so the
budget ends with completed, failed and not-run items listed instead of silently shortened runs.
Choices between stages (the input rate after C0, the candidates after E1) follow fixed rules
written here, not judgement after seeing the numbers.
"""
import argparse
import json
import math
import random
import statistics
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import runner  # noqa: E402
import topology as topo  # noqa: E402
import trace as tracegen  # noqa: E402

SEEDS = [1251, 1252, 1253]
EXTRA_SEEDS = [1254, 1255]
KAFKA = ['kafka-bucket', 'kafka-retry']
ALL = KAFKA + ['db-inbox']
MINUTES = dict(e1=9, e2=9, e3=6, e4=6, c0=6)


def e1_spec(candidate, seed, rate, backlog=200, label='e1'):
    return dict(label=f'{label}-{candidate}-s{seed}', stage=label, candidate=candidate, seed=seed, backlogLimit=backlog,
                phases=[('warmup', 90, rate), ('baseline', 90, rate), ('fault', 180, rate), ('recovery', 90, rate)],
                fault='fault', judgedPhases=['fault', 'recovery'], drainSeconds=60)


def e3_spec(candidate, seed, rate, condition):
    spec = dict(label=f'e3-{condition}-{candidate}-s{seed}', stage='e3', candidate=candidate, seed=seed,
                phases=[('warmup', 90, rate), ('scenario', 150, rate)], scenarioPhase='scenario', drainSeconds=90,
                judgedPhases=['scenario'])
    if condition == 'scale':
        spec.update(scenario='scale', fault='scenario', partnersAtStart=1)
    else:
        spec.update(scenario='stale', partnersAtStart=2)
    return spec


def e4_spec(candidate, seed, rate):
    return dict(label=f'e4-{candidate}-s{seed}', stage='e4', candidate=candidate, seed=seed,
                phases=[('warmup', 90, rate), ('input', 120, rate), ('after', 60, rate)], fault='input',
                scenario='observer-pause', scenarioPhase='input', judgedPhases=['input', 'after'], drainSeconds=60)


def c0_spec(candidate, seed):
    return dict(label=f'c0-{candidate}', stage='c0', candidate=candidate, seed=seed,
                phases=[('warmup', 90, 40), ('r40', 60, 40), ('r80', 60, 80), ('r160', 60, 160)], drainSeconds=60)


class Suite:
    def __init__(self, evidence, budget_minutes):
        self.root = Path(evidence)
        self.root.mkdir(parents=True, exist_ok=True)
        self.deadline = time.monotonic() + budget_minutes * 60
        self.began = time.time()
        self.log = self.root/'suite.jsonl'

    def note(self, **entry):
        entry['time'] = int(time.time() * 1000)
        with self.log.open('a') as out:
            out.write(json.dumps(entry) + '\n')
        print(json.dumps(entry), flush=True)

    def remaining_minutes(self):
        return (self.deadline - time.monotonic()) / 60

    def run(self, spec, minutes):
        if self.remaining_minutes() < minutes + 1:
            self.note(event='not_run', reason='budget', spec=spec)
            return None, None
        self.note(event='planned', spec=spec)
        path, summary = runner.execute(self.root/'runs', spec)
        result = json.loads((path/'checks.json').read_text()) if (path/'checks.json').exists() else None
        self.note(event='finished', run=path.name, summary=summary)
        return path, result

    # ---------- G0
    def g0(self):
        results = {}
        results['checker'] = self.g0_checker()
        results['source'] = self.g0_source()
        results['mock'] = self.g0_mock()
        results['observer'] = self.g0_observer()
        passed = all(r.get('passed') for r in results.values())
        (self.root/'g0.json').write_text(json.dumps(dict(passed=passed, **results), indent=1))
        self.note(event='g0', passed=passed, results={k: v.get('passed') for k, v in results.items()})
        return passed

    def g0_checker(self):
        out = subprocess.run([sys.executable, str(Path(__file__).parent/'test_check.py')], capture_output=True, text=True)
        return dict(passed=out.returncode == 0, output=out.stdout[-2000:] + out.stderr[-2000:])

    def _direct_load(self, name, target, url, rate, seconds):
        directory = self.root/'g0'/name
        directory.mkdir(parents=True, exist_ok=True)
        entries = tracegen.build(1250, [(name, seconds, rate)], run_id=name)
        tracegen.write(directory/'trace.jsonl', entries, 'order')
        topo.copy_to(topo.PRODUCER, directory/'trace.jsonl', 'trace.jsonl')
        topo.copy_to(topo.PRODUCER, Path(__file__).parent/'load.py', 'load.py')
        start = runner.now_ms() + 3000
        python = sys.executable if topo.LOCAL else 'python3'
        out = topo.run(topo.PRODUCER, f'{python} load.py --trace trace.jsonl --target {target} --url {url} '
                                      f'--start-at {start} --out load.jsonl', timeout=seconds + 600)
        topo.copy_from(topo.PRODUCER, 'load.jsonl', directory/'load.jsonl')
        records = [json.loads(x) for x in (directory/'load.jsonl').read_text().splitlines()]
        return directory, start, records, json.loads(out.strip().splitlines()[-1])

    def g0_source(self):
        """Source path alone at twice the highest planned input: accept, commit, relay to Kafka."""
        spec = dict(label='g0-source', candidate='kafka-bucket', seed=1250, phases=[('g0', 120, 320)])
        run = runner.Run(self.root/'g0', spec)
        try:
            run.reset()
            run.start_source()
            directory, start, records, summary = self._direct_load('source-path', 'source', topo.SOURCE.url(), 320, 120)
            accepted = sum(1 for r in records if r.get('status') == 200)
            late = sorted(r['sentAt'] - r['plannedAt'] for r in records)
            accept = sorted(r['doneAt'] - r['sentAt'] for r in records)
            time.sleep(10)
            ends = self._end_offsets(run.topic)
            result = dict(events=len(records), accepted=accepted, published=ends, lateP99Ms=self._p(late, .99),
                          acceptP99Ms=self._p(accept, .99), loadSummary=summary)
            result['passed'] = accepted == len(records) and ends == accepted and result['lateP99Ms'] <= 10
            return result
        except Exception as error:
            return dict(passed=False, error=repr(error))
        finally:
            run.stop_all()

    def _end_offsets(self, topic):
        if topo.LOCAL:
            out = subprocess.run(['docker', 'exec', 'partner-isolation-kafka-1', '/opt/kafka/bin/kafka-get-offsets.sh',
                                  '--bootstrap-server', 'localhost:9092', '--topic', topic], capture_output=True, text=True).stdout
        else:
            out = topo.run(topo.KAFKA_HOSTS[0], f'docker exec kafka /opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server localhost:9092 --topic {topic}')
        return sum(int(line.rsplit(':', 1)[1]) for line in out.splitlines() if line.count(':') >= 2)

    def g0_mock(self):
        """Seller API mock alone at twice the highest planned input, 20ms service time."""
        try:
            runner.http(topo.MOCK.url() + '/reset', dict(name=f'g0-mock-{int(time.time())}'))
            runner.http(topo.MOCK.url() + '/control', {'sellerId': '*', 'delayMs': runner.NORMAL_DELAY_MS})
            directory, start, records, summary = self._direct_load('mock-direct', 'mock', topo.MOCK.url(), 320, 120)
            snapshot = runner.http(topo.MOCK.url() + '/snapshot', timeout=120)
            server_extra = sorted(a['end_at'] - a['start_at'] - runner.NORMAL_DELAY_MS for a in snapshot['attempts'] if a['end_at'])
            client_extra = sorted(r['doneAt'] - r['sentAt'] - runner.NORMAL_DELAY_MS for r in records if r.get('status') == 200)
            ok = sum(1 for r in records if r.get('status') == 200)
            result = dict(events=len(records), ok=ok, effects=len(snapshot['effects']), serverExtraP99Ms=self._p(server_extra, .99),
                          clientExtraP99Ms=self._p(client_extra, .99), lateP99Ms=summary.get('lateP99Ms'))
            result['passed'] = ok == len(records) and len(snapshot['effects']) == ok and result['clientExtraP99Ms'] <= 5
            return result
        except Exception as error:
            return dict(passed=False, error=repr(error))

    def g0_observer(self):
        """Observer on and off in alternating 60s windows (order from the seed) under normal load."""
        order = [True, False, True, False]
        if random.Random(1250).random() < 0.5:
            order = [not x for x in order]
        spec = dict(label='g0-observer', stage='g0', candidate='kafka-bucket', seed=1250,
                    phases=[('warmup', 90, 80)] + [(f'w{i + 1}', 60, 80) for i in range(4)], drainSeconds=30,
                    scenario='observer-off', scenarioPhase='warmup',
                    observerWindows=[(f'w{i + 1}', not on) for i, on in enumerate(order)])
        path, summary = runner.execute(self.root/'g0', spec)
        checks = json.loads((path/'checks.json').read_text()) if (path/'checks.json').exists() else None
        if not checks:
            return dict(passed=False, summary=summary)
        windows = {f'w{i + 1}': ('on' if on else 'off', checks['latency'].get(f'w{i + 1}', {}).get('normal', {})) for i, on in enumerate(order)}
        on = [w for s, w in windows.values() if s == 'on']
        off = [w for s, w in windows.values() if s == 'off']
        p99 = lambda ws: statistics.median([w['p99Ms'] for w in ws if w.get('p99Ms') is not None])
        done = lambda ws: sum(w['delivered'] for w in ws) / max(1, sum(w['n'] for w in ws))
        diff_p99 = abs(p99(on) - p99(off))
        result = dict(windows=windows, p99On=p99(on), p99Off=p99(off), completionOn=done(on), completionOff=done(off))
        result['passed'] = abs(done(on) - done(off)) <= 0.03 and diff_p99 <= max(0.05 * p99(off), 10)
        return result

    @staticmethod
    def _p(values, q):
        return values[max(0, math.ceil(q * len(values)) - 1)] if values else None

    # ---------- C0
    def c0(self):
        rates = {}
        for candidate in random.Random(1250).sample(ALL, len(ALL)):
            path, result = self.run(c0_spec(candidate, 1250), MINUTES['c0'])
            rates[candidate] = self.stable_at_80(path) if path else False
        rate = 80 if all(rates.values()) else 40
        self.note(event='c0', stableAt80=rates, rate=rate)
        return rate

    def stable_at_80(self, path):
        """Last 30s of the 80 events/s step: completions >= 95% of input, backlog growth <= 5% of it."""
        info = json.loads((path/'run.json').read_text())
        begin, end = info['phaseBounds']['r80']
        t1, t2 = info['startAt'] + end - 30_000, info['startAt'] + end
        obligations = json.loads((path/'obligations.json').read_text())
        effects = json.loads((path/'mock.json').read_text())['effects']
        created = [o['created_at'] for o in obligations]
        arrived = sum(1 for c in created if t1 <= c < t2)
        completed = sum(1 for e in effects if t1 <= e['effect_at'] < t2)
        done = sorted(e['effect_at'] for e in effects)
        backlog = lambda t: sum(1 for c in created if c < t) - sum(1 for d in done if d < t)
        growth = backlog(t2) - backlog(t1)
        return arrived > 0 and completed >= 0.95 * arrived and growth <= 0.05 * arrived

    # ---------- selection rules
    @staticmethod
    def judged_miss(result):
        phases = [result['latency'].get(p, {}).get('normal', {}) for p in ('fault', 'recovery')]
        return max((p.get('missRate') or 0) for p in phases)

    def pick(self, results):
        """E3 Kafka candidate and final structure, by the rules fixed in the design."""
        def safe(c):
            return results[c] and all(r and r['safety']['passed'] for r in results[c])

        def median_miss(c):
            return statistics.median(self.judged_miss(r) for r in results[c] if r) if results[c] else math.inf

        def meets(c):
            return safe(c) and len(results[c]) == 3 and all(r and self.judged_miss(r) <= 0.01 for r in results[c])
        kafka = [c for c in KAFKA if safe(c)] or KAFKA
        kafka.sort(key=lambda c: (median_miss(c), 0 if c == 'kafka-bucket' else 1))
        best = kafka[0]
        if len(kafka) > 1 and abs(median_miss(kafka[0]) - median_miss(kafka[1])) < 0.005:
            best = 'kafka-bucket'
        if meets(best):
            final = best
        elif meets('db-inbox'):
            final = 'db-inbox'
        else:
            final = min([best, 'db-inbox'], key=lambda c: (median_miss(c), 0 if c != 'db-inbox' else 1))
        return best, final

    # ---------- whole plan
    def all(self, stages, rate=None):
        if 'g0' in stages and not self.g0():
            self.note(event='stopped', reason='G0 failed; fix the instruments before comparing')
            return
        if 'c0' in stages:
            rate = self.c0()
        rate = rate or 80
        results = {c: [] for c in ALL}
        if 'e1' in stages:
            for seed in SEEDS:
                for candidate in random.Random(seed).sample(ALL, len(ALL)):
                    _, result = self.run(e1_spec(candidate, seed, rate), MINUTES['e1'])
                    results[candidate].append(result)
        best, final = self.pick(results) if 'e1' in stages else ('kafka-bucket', 'kafka-bucket')
        self.note(event='selection', kafkaForE3=best, final=final,
                  medians={c: [self.judged_miss(r) for r in rs if r] for c, rs in results.items()})
        if 'e2' in stages:
            for seed in SEEDS:
                self.run(e1_spec('db-inbox', seed, rate, backlog=2000, label='e2'), MINUTES['e2'])
        if 'e3' in stages:
            for seed in SEEDS:
                cells = [(c, cond) for c in ['db-inbox', best] for cond in ['scale', 'stale']]
                for candidate, condition in random.Random(seed + 30).sample(cells, len(cells)):
                    self.run(e3_spec(candidate, seed, rate, condition), MINUTES['e3'])
        if 'e4' in stages:
            for seed in SEEDS:
                self.run(e4_spec(final, seed, rate), MINUTES['e4'])
        if 'e1' in stages and self.remaining_minutes() >= 2 * 2 * MINUTES['e1'] + 4:
            for seed in EXTRA_SEEDS:
                for candidate in random.Random(seed).sample(['db-inbox', best], 2):
                    self.run(e1_spec(candidate, seed, rate, label='e1x'), MINUTES['e1'])
        self.note(event='done', remainingMinutes=round(self.remaining_minutes(), 1),
                  elapsedMinutes=round((time.time() - self.began) / 60, 1))


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--evidence', required=True)
    p.add_argument('--stages', default='g0,c0,e1,e2,e3,e4')
    p.add_argument('--budget-minutes', type=float, default=300)
    p.add_argument('--rate', type=int, help='skip C0 and use this input rate')
    p.add_argument('--one', help='run a single spec given as JSON, for smoke tests')
    a = p.parse_args()
    suite = Suite(a.evidence, a.budget_minutes)
    if a.one:
        print(runner.execute(suite.root/'runs', json.loads(a.one)))
    else:
        suite.all(a.stages.split(','), a.rate)
    (suite.root/'DONE').write_text('done\n')
