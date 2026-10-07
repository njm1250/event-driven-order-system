#!/usr/bin/env python3
"""Throughput sweep summary: where each structure saturates and which resource moved first.
Three runs per cell are shown as raw values, median and range; no means or deviations."""
import argparse
import json
import statistics
from pathlib import Path
from evidence_paths import validate_evidence_path

MODES = ['sequential', 'async', 'parallel-consumer', 'retry-topic', 'inbox']

p = argparse.ArgumentParser(); p.add_argument('--evidence', required=True); a = p.parse_args()
root = Path(a.evidence).resolve()
validate_evidence_path(root)
items = json.loads((root/'index.json').read_text())


def cases(mode, scenario, rate=None):
    return [x for x in items if x['settings'].get('mode') == mode and x['settings'].get('scenario') == scenario
            and x['settings'].get('workload') == 'sweep' and (rate is None or x['settings'].get('targetEventsPerSecond') == rate)]


def triple(values, unit=''):
    values = [v for v in values if v is not None]
    if not values: return '-'
    raw = ' / '.join(f'{v:,.0f}' if abs(v) >= 10 else f'{v:.2f}' for v in values)
    med = statistics.median(values)
    return f'{raw} (중앙 {med:,.0f}{unit}, 범위 {min(values):,.0f}~{max(values):,.0f})' if len(values) > 1 else f'{raw}{unit}'


def median(values):
    values = [v for v in values if v is not None]
    return statistics.median(values) if values else None


def host(c, name, key):
    hosts = c['metrics'].get('hosts') or {}
    return (hosts.get(name) or {}).get(key)


def kafka_cpu(c):
    hosts = c['metrics'].get('hosts') or {}
    values = [v.get('cpuBusy') for k, v in hosts.items() if k.startswith('kafka') and v]
    return max(values) if values else None


def mysql(c, key):
    return (c['metrics'].get('mysql') or {}).get(key)


rates = sorted({x['settings'].get('targetEventsPerSecond') for x in items if x['settings'].get('workload') == 'sweep' and x['settings'].get('scenario') == 'clean'})
summary = dict(rates=rates, cells={}, failover={})
lines = ['# 처리량 한계 (장애 없음)', '',
         '판매처 20곳, partition 4, 입력 12초. 각 칸은 3회 원값, 중앙값, 범위. 처리량은 첫 발생부터 마지막 전달까지의 실측이다.', '']
for mode in MODES:
    if not cases(mode, 'clean'): continue
    lines += [f'## {mode}', '', '| 입력 eps | 처리 eps | 정상 p50 ms | 정상 p99 ms | 최대 lag |', '| --- | --- | --- | --- | --- |']
    for rate in rates:
        cs = cases(mode, 'clean', rate)
        if not cs: continue
        m = lambda key: [c['metrics'].get(key) for c in cs]
        lines.append(f"| {rate} | {triple(m('deliveredEventsPerSecond'))} | {triple([c['metrics']['latency'][0]['p50Ms'] for c in cs])} | "
                     f"{triple([c['metrics']['latency'][0]['p99Ms'] for c in cs])} | {triple(m('peakCommittedRemaining'))} |")
        summary['cells'][f'{mode}@{rate}'] = dict(
            runs=[c['run'] for c in cs],
            delivered=m('deliveredEventsPerSecond'), p50=[c['metrics']['latency'][0]['p50Ms'] for c in cs],
            p99=[c['metrics']['latency'][0]['p99Ms'] for c in cs], peakLag=m('peakCommittedRemaining'),
            partnerCpu=median([host(c, 'partner', 'cpuBusy') for c in cs]), mysqlCpu=median([host(c, 'mysql', 'cpuBusy') for c in cs]),
            kafkaCpuMax=median([kafka_cpu(c) for c in cs]),
            stealMax=max([v.get('cpuSteal') or 0 for c in cs for v in (c['metrics'].get('hosts') or {}).values() if v] or [0]),
            mysqlDiskWriteLatencyMs=median([host(c, 'mysql', 'diskWriteLatencyMs') for c in cs]),
            mysqlDiskWritesPerSecond=median([host(c, 'mysql', 'diskWritesPerSecond') for c in cs]),
            commitsPerSecond=median([mysql(c, 'commitsPerSecond') for c in cs]), questionsPerSecond=median([mysql(c, 'questionsPerSecond') for c in cs]),
            insertAvgUs=median([mysql(c, 'insertAvgUs') for c in cs]), updateAvgUs=median([mysql(c, 'updateAvgUs') for c in cs]),
            commitAvgUs=median([mysql(c, 'commitAvgUs') for c in cs]), redoFsyncs=median([mysql(c, 'redoFsyncs') for c in cs]),
            redoMiscAvgUs=median([mysql(c, 'redoMiscAvgUs') for c in cs]), gcMillis=median(m('gcMillis')),
            producerLatencyPeakMs=median(m('producerRequestLatencyAvgMsPeak')))
    lines.append('')

lines += ['## 자원 (3회 중앙값)', '',
          '| 구조 @ 입력 eps | partner CPU | MySQL CPU | Kafka CPU(최대) | steal | MySQL 디스크 쓰기 지연 ms | commit/s | 질의/s | INSERT·UPDATE·COMMIT µs | GC ms | 발행 지연 ms |',
          '| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |']
pct = lambda v: '-' if v is None else f'{v * 100:.0f}%'
num = lambda v: '-' if v is None else (f'{v:,.0f}' if abs(v) >= 10 else f'{v:.2f}')
for key, c in summary['cells'].items():
    lines.append(f"| {key} | {pct(c['partnerCpu'])} | {pct(c['mysqlCpu'])} | {pct(c['kafkaCpuMax'])} | {pct(c['stealMax'])} | "
                 f"{num(c['mysqlDiskWriteLatencyMs'])} | {num(c['commitsPerSecond'])} | {num(c['questionsPerSecond'])} | "
                 f"{num(c['insertAvgUs'])}·{num(c['updateAvgUs'])}·{num(c['commitAvgUs'])} | {num(c['gcMillis'])} | {num(c['producerLatencyPeakMs'])} |")

failover = [x for x in items if x['settings'].get('scenario') == 'broker-failover']
if failover:
    lines += ['', '## broker 1대 강제 종료 (RF 3, min ISR 2, 160 eps)', '', '| 구조 | 정상 p99 ms (3회) | checker | 최대 lag |', '| --- | --- | --- | --- |']
    for mode in MODES:
        cs = [x for x in failover if x['settings'].get('mode') == mode]
        if not cs: continue
        lines.append(f"| {mode} | {triple([c['metrics']['latency'][0]['p99Ms'] for c in cs])} | "
                     f"{sum(c['metrics']['checker']['passed'] for c in cs)}/{len(cs)} 통과 | {triple([c['metrics'].get('peakCommittedRemaining') for c in cs])} |")
        summary['failover'][mode] = dict(runs=[c['run'] for c in cs], p99=[c['metrics']['latency'][0]['p99Ms'] for c in cs],
                                         passed=[c['metrics']['checker']['passed'] for c in cs])
(root/'sweep.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2))
(root/'SWEEP.md').write_text('\n'.join(lines) + '\n')
print('\n'.join(lines))
