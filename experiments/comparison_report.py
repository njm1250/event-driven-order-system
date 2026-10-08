#!/usr/bin/env python3
"""Summarize the standard-remedy comparison: where each structure keeps or loses normal sellers."""
import argparse
import json
import statistics
from pathlib import Path
from evidence_paths import validate_evidence_path

MODES = ['sequential', 'async', 'circuit-breaker', 'parallel-consumer', 'retry-topic', 'inbox', 'inbox-batch', 'inbox-lean']
SLO_MS = 1500

p = argparse.ArgumentParser(); p.add_argument('--evidence', required=True); a = p.parse_args()
root = Path(a.evidence).resolve()
validate_evidence_path(root)
items = json.loads((root/'index.json').read_text())

def runs(mode, scenario, workload, partitions=1):
    return [x for x in items if x['settings'].get('mode') == mode and x['settings'].get('scenario') == scenario
            and x['settings'].get('workload', 'pair') == workload and x['settings'].get('partitions', 1) == partitions
            and x['settings'].get('observe', True) and x['metrics']['checker']['passed']][-3:]

def median(values):
    values = [v for v in values if v is not None]
    return statistics.median(values) if values else None

def peak_concurrency(run):
    remote = json.loads((root/run['run']/'remote.json').read_text())
    changes = []
    for row in remote['attempts']:
        group = 'slow' if row['seller_id'] == 'slow' else 'normal'
        changes += [(row['start_at'], 1, group), (row['end_at'] or row['start_at'], -1, group)]
    current = dict(slow=0, normal=0); peak = dict(slow=0, normal=0)
    for _, delta, group in sorted(changes, key=lambda x: (x[0], x[1])):
        current[group] += delta; peak[group] = max(peak[group], current[group])
    return peak

def db_writes(run):
    directory = root/run['run']
    if not (directory/'db-io-before.json').exists(): return None
    count = lambda name: sum(int(x['COUNT_WRITE']) for x in json.loads((directory/name).read_text()))
    return count('db-io-after.json') - count('db-io-before.json')

def summarize(cases):
    if not cases: return None
    normal = [c['metrics']['latency'][0]['p99Ms'] for c in cases]
    return dict(
        runs=[c['run'] for c in cases],
        normalP99=normal, normalP99Median=median(normal),
        normalSamples=cases[0]['metrics']['latency'][0].get('samples'),
        sloPassed=sum(v <= SLO_MS for v in normal),
        slowP99Median=median([c['metrics']['latency'][1]['p99Ms'] for c in cases]),
        slowDrainAfterInputMsMedian=median([c['metrics'].get('slowDrainAfterInputMs') for c in cases]),
        peakSlowCalls=max(peak_concurrency(c)['slow'] for c in cases),
        externalRequestsMedian=median([c['metrics'].get('externalRequests') for c in cases]),
        retryTopicRecordsMedian=median([c['metrics'].get('retryTopicRecords') for c in cases]),
        partnerDbWritesMedian=median([db_writes(c) for c in cases]),
        cpuSecondsMedian=median([c['metrics']['workloadCpuNanos'] / 1e9 if c['metrics'].get('workloadCpuNanos') else None for c in cases]),
    )

result = dict(sloMs=SLO_MS, pair={}, market={}, hang={}, correctness={})
for mode in MODES:
    result['pair'][mode] = summarize(runs(mode, 'api', 'pair'))
    result['market'][mode] = {f'p{n}': summarize(runs(mode, 'api', 'market', n)) for n in [1, 4]}
    result['hang'][mode] = summarize(runs(mode, 'hang', 'market', 4))
    checks = [x for x in items if x['settings'].get('mode') == mode and x['settings'].get('scenario') not in {'api', 'clean', 'hang'}]
    result['correctness'][mode] = dict(runs=len(checks), passed=sum(x['metrics']['checker']['passed'] for x in checks),
                                       scenarios=sorted({x['settings'].get('scenario') + ('/' + x['settings'].get('boundary', '') if x['settings'].get('boundary') else '') for x in checks}))
(root/'comparison.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))

def fmt(value):
    return '-' if value is None else (f'{value:,.0f}' if isinstance(value, (int, float)) else str(value))

lines = ['# 표준 해법 비교', '',
         f'정상 판매처 허용 p99 {SLO_MS}ms는 측정 전에 고정했다. 각 칸은 같은 조건 3회의 중앙값이다. 모든 실행은 외부 API 이력과 DB를 대조하는 독립 checker를 통과했다.', '',
         '## 판매처 20곳, 큰 판매처 하나(주문 20%)가 6초간 600ms 지연', '',
         '주문 300건 × CREATE/CHANGE/CANCEL = 900 이벤트, 정상 판매처 표본 720개. 주문 key(sellerId+orderId)로 partition을 나눈다.', '',
         '| 구조 | 정상 p99 (partition 1) | 정상 p99 (partition 4) | 느린 판매처 최대 동시 호출 (p4) | 외부 요청 수 (p4) | retry topic 기록 (p4) | partner DB 행 쓰기 (p4) |',
         '| --- | --- | --- | --- | --- | --- | --- |']
for mode in MODES:
    p1, p4 = result['market'][mode]['p1'], result['market'][mode]['p4']
    if not p1 and not p4: continue
    get = lambda s, k: s.get(k) if s else None
    lines.append(f"| {mode} | {fmt(get(p1,'normalP99Median'))}ms | {fmt(get(p4,'normalP99Median'))}ms | {fmt(get(p4,'peakSlowCalls'))} | "
                 f"{fmt(get(p4,'externalRequestsMedian'))} | {fmt(get(p4,'retryTopicRecordsMedian'))} | {fmt(get(p4,'partnerDbWritesMedian'))} |")
lines += ['', '## 같은 판매처가 12초간 3초씩 응답하지 않을 때 (hang, partition 4)', '',
          '| 구조 | 정상 p99 (3회) | 중앙값 | 느린 판매처 p99 중앙값 | 입력 종료 후 해소 |', '| --- | --- | --- | --- | --- |']
for mode in MODES:
    s = result['hang'][mode]
    if s: lines.append(f"| {mode} | {s['normalP99']} | {fmt(s['normalP99Median'])}ms | {fmt(s['slowP99Median'])}ms | {fmt(s['slowDrainAfterInputMsMedian'])}ms |")
lines += ['', '## 판매처 2곳, partition 1개 (첫 비교와 같은 조건)', '', '| 구조 | 정상 p99 (3회) | 중앙값 |', '| --- | --- | --- |']
for mode in MODES:
    s = result['pair'][mode]
    if s: lines.append(f"| {mode} | {s['normalP99']} | {fmt(s['normalP99Median'])}ms |")
lines += ['', '## 실패 경계 정합성', '', '| 구조 | 통과/실행 | 시나리오 |', '| --- | --- | --- |']
for mode in MODES:
    c = result['correctness'][mode]
    if c['runs']: lines.append(f"| {mode} | {c['passed']}/{c['runs']} | {', '.join(c['scenarios'])} |")
(root/'COMPARISON.md').write_text('\n'.join(lines) + '\n')
print('\n'.join(lines))
