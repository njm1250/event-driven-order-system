#!/usr/bin/env python3
"""Summarizes a suite directory into report.json and REPORT.md: per stage and candidate, the raw
values of every run next to their median and range, never only the median."""
import argparse
import collections
import json
import statistics
from pathlib import Path

TARGET_MS = 1500


def load(path, default=None):
    return json.loads(path.read_text()) if path.exists() else default


def lines(path):
    return [json.loads(x) for x in path.read_text().splitlines() if x.strip()] if path.exists() else []


def cohort(checks, phase, group='normal'):
    return checks['latency'].get(phase, {}).get(group, {})


def buffer_timeline(run_dir, run, checks):
    """When the buffer filled and when normal sellers started to miss, relative to fault start."""
    fault = run.get('fault')
    if not fault:
        return {}
    samples = lines(run_dir/'metrics.jsonl')
    limit = run['spec'].get('backlogLimit', 200)
    filled = None
    peak_inbox = peak_pc = peak_retry = 0
    for s in samples:
        p = (s.get('partners') or [{}])[0]
        inbox = sum((p.get('inboxPendingBySeller') or {}).values())
        peak_inbox = max(peak_inbox, inbox)
        peak_pc = max(peak_pc, p.get('pcWorkRemaining') or 0)
        peak_retry = max(peak_retry, p.get('retryRemaining') or 0)
        held = inbox if run['spec']['candidate'] == 'db-inbox' else (p.get('pcWorkRemaining') or 0)
        if filled is None and s['time'] >= fault['from'] and held >= 0.95 * limit:
            filled = s['time'] - fault['from']
    obligations = {o['event_id']: o for o in load(run_dir/'obligations.json', [])}
    effects = {e['event_id']: e['effect_at'] for e in load(run_dir/'mock.json', {}).get('effects', [])}
    trace = {e['event']['eventId']: e for e in lines(run_dir/'trace.jsonl')}
    first_miss = None
    for event_id, o in obligations.items():
        t = trace.get(event_id)
        if not t or t['event']['sellerId'] == run.get('faultSeller', 'slow') or o['created_at'] < fault['from']:
            continue
        if effects.get(event_id, float('inf')) - o['created_at'] > TARGET_MS:
            moment = o['created_at'] - fault['from']
            first_miss = moment if first_miss is None else min(first_miss, moment)
    return dict(bufferFilledAfterMs=filled, firstNormalMissAfterMs=first_miss, peakInboxPending=peak_inbox,
                peakPcHeld=peak_pc, peakRetryTopicBacklog=peak_retry)


def stale_timeline(run_dir, run, checks):
    stale = run.get('staleOwner')
    events = {e['action']: e for e in run.get('events', [])}
    if not stale:
        return dict(injected=False, reason=events.get('injection_invalid', {}).get('reason'))
    attempts = [a for a in load(run_dir/'mock.json', {}).get('attempts', []) if a['event_id'] == stale['eventId']]
    attempts.sort(key=lambda a: a['start_at'])
    takeover = events.get('takeover_committed', {}).get('afterStopMs')
    second_call = next((a['start_at'] - stale['stoppedAt'] for a in attempts if a['start_at'] > stale['stoppedAt']), None)
    return dict(injected=True, takeoverCommitAfterStopMs=takeover, secondCallAfterStopMs=second_call,
                callsForHeldEvent=len(attempts), callInstances=len({a.get('instance') for a in attempts}))


def summarize(root):
    rows = []
    for run_dir in sorted((root/'runs').iterdir()):
        run, checks = load(run_dir/'run.json'), load(run_dir/'checks.json')
        if not run or not checks:
            rows.append(dict(run=run_dir.name, error=(load(run_dir/'summary.json', {}) or {}).get('error')))
            continue
        spec = run['spec']
        row = dict(run=run_dir.name, stage=spec.get('stage', spec['label'].split('-')[0]), candidate=spec['candidate'],
                   seed=spec['seed'], scenario=spec.get('scenario'), backlogLimit=spec.get('backlogLimit', 200),
                   safety=checks['safety']['passed'], liveness=checks['liveness']['passed'], budget=checks['budget']['passed'],
                   undelivered=checks['liveness']['undelivered'], sellerPeak=checks['budget']['sellerPeak'],
                   sellerOverLimitMs=sum(checks['budget']['sellerOverLimitMs'].values()), globalPeak=checks['budget']['globalPeak'],
                   extraRequests=checks['budget']['extraRequests'], retryPerSecondPeak=checks['budget']['retryPerSecondPeak'],
                   requests=checks['budget']['requests'], evidence=checks['evidence'])
        for phase in checks['latency']:
            if phase.startswith('_'):
                continue
            for group in ('normal', 'fault'):
                c = cohort(checks, phase, group)
                if c:
                    row[f'{phase}.{group}'] = {k: c.get(k) for k in ('n', 'p50Ms', 'p99Ms', 'maxMs', 'missRate', 'censored', 'internalMissRate')}
        row['observability'] = checks.get('observability')
        row.update(buffer_timeline(run_dir, run, checks))
        if spec.get('scenario') == 'stale':
            row['stale'] = stale_timeline(run_dir, run, checks)
        if spec.get('scenario') == 'scale':
            row['requestsByInstance'] = list(checks['budget']['requestsByInstance'].values())
        rows.append(row)
    return rows


def spread(values):
    values = [v for v in values if v is not None]
    if not values:
        return '-'
    shown = ' / '.join(f'{v:.4g}' if isinstance(v, float) else str(v) for v in values)
    return f'{shown} (중앙 {statistics.median(values):.4g})'


def markdown(rows):
    out = ['# 실험 결과 요약', '', '각 칸은 실행별 원값과 중앙값이다. normal은 장애 대상이 아닌 판매처, 기한은 1,500ms.', '']
    groups = collections.defaultdict(list)
    for r in rows:
        if 'stage' in r:
            groups[(r['stage'], r['candidate'], r.get('scenario'), r['backlogLimit'])].append(r)
    for (stage, candidate, scenario, limit), rs in sorted(groups.items(), key=lambda kv: (kv[0][0], str(kv[0][2]), kv[0][1])):
        out.append(f'## {stage} {candidate}' + (f' {scenario}' if scenario else '') + (f' (한도 {limit})' if limit != 200 else '') + f' — {len(rs)}회')
        out.append('')
        out.append(f"- 안전성 {sum(r['safety'] for r in rs)}/{len(rs)}, 진행성 {sum(r['liveness'] for r in rs)}/{len(rs)}, 예산 {sum(r['budget'] for r in rs)}/{len(rs)}")
        out.append(f"- 판매처 최대 동시 호출: {spread([r['sellerPeak'] for r in rs])}, 상한 초과 시간 ms: {spread([r['sellerOverLimitMs'] for r in rs])}")
        out.append(f"- 추가 요청: {spread([r['extraRequests'] for r in rs])}, 미전달: {spread([r['undelivered'] for r in rs])}")
        phases = sorted({k.split('.')[0] for r in rs for k in r if '.' in k and k.endswith('.normal')})
        for phase in phases:
            cells = [r.get(f'{phase}.normal') or {} for r in rs]
            out.append(f"- {phase} normal: p99 ms {spread([c.get('p99Ms') for c in cells])}, 기한 초과율 {spread([c.get('missRate') for c in cells])}")
        if any(r.get('bufferFilledAfterMs') is not None or r.get('firstNormalMissAfterMs') is not None for r in rs):
            out.append(f"- 장애 시작 후 버퍼 95% 도달 ms: {spread([r.get('bufferFilledAfterMs') for r in rs])}, 첫 정상 판매처 기한 초과 ms: {spread([r.get('firstNormalMissAfterMs') for r in rs])}")
            out.append(f"- 최대 보유: inbox {spread([r.get('peakInboxPending') for r in rs])}, PC {spread([r.get('peakPcHeld') for r in rs])}, retry topic {spread([r.get('peakRetryTopicBacklog') for r in rs])}")
        if scenario == 'stale':
            st = [r.get('stale') or {} for r in rs]
            out.append(f"- 정지 후 이전 commit ms: {spread([s.get('takeoverCommitAfterStopMs') for s in st])}, 정지 후 재호출 ms: {spread([s.get('secondCallAfterStopMs') for s in st])}, 같은 이벤트 호출 수: {spread([s.get('callsForHeldEvent') for s in st])}")
        if scenario == 'scale':
            out.append(f"- 인스턴스별 요청: {[r.get('requestsByInstance') for r in rs]}")
        obs = [r.get('observability') or {} for r in rs]
        if any(obs):
            out.append(f"- 관측: 탐지 최대 ms {spread([o.get('detectionMaxMs') for o in obs])}, 미탐 ms {spread([o.get('missedMs') for o in obs])}, 오탐 ms {spread([o.get('falseAlertMs') for o in obs])}, UNKNOWN 표본 {spread([o.get('unknownSamples') for o in obs])}, 나이 오차 p95 ms {spread([o.get('ageErrorP95Ms') for o in obs])}, 최대 lag {spread([o.get('lagPeak') for o in obs])}")
        out.append('')
    errors = [r for r in rows if 'error' in r]
    if errors:
        out += ['## 오류로 끝난 실행', ''] + [f"- {r['run']}: {r['error']}" for r in errors]
    return '\n'.join(out) + '\n'


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--evidence', required=True)
    a = p.parse_args()
    root = Path(a.evidence)
    rows = summarize(root)
    (root/'report.json').write_text(json.dumps(rows, indent=1))
    (root/'REPORT.md').write_text(markdown(rows))
    print(markdown(rows))
