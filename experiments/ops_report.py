#!/usr/bin/env python3
"""Operational scenarios (graceful restart, scale-out) and the inbox backlog signal versus Kafka lag."""
import argparse
import collections
import json
import statistics
from pathlib import Path
from evidence_paths import validate_evidence_path

p = argparse.ArgumentParser(); p.add_argument('--evidence', required=True); a = p.parse_args()
root = Path(a.evidence).resolve()
validate_evidence_path(root)


def load(path, default=None):
    return json.loads(path.read_text()) if path.exists() else default


def peak_per_seller(attempts):
    """Highest number of overlapping partner calls for any single seller."""
    by_seller = collections.defaultdict(list)
    for row in attempts:
        by_seller[row['seller_id']] += [(row['start_at'], 1), (row['end_at'] or row['start_at'], -1)]
    peaks = {}
    for seller, changes in by_seller.items():
        current = peak = 0
        for _, delta in sorted(changes):
            current += delta; peak = max(peak, current)
        peaks[seller] = peak
    return peaks


def traces(directory, stage):
    count = 0
    for log in directory.glob('partner-integration-service*.log'):
        for line in log.read_text(errors='replace').splitlines():
            if line.startswith('TRACE ') and f'"stage":"{stage}"' in line:
                count += 1
    return count


ops, sli = [], []
for directory in sorted(x for x in root.iterdir() if x.is_dir()):
    manifest = load(directory/'manifest.json')
    summary = load(directory/'summary.json')
    if not manifest or not summary:
        continue
    settings = manifest['settings']
    if settings['scenario'] in {'graceful-restart', 'scale-out'}:
        controller = load(directory/'controller.json', [])
        stopped = next((x for x in controller if x['action'] == 'partner_stopped'), {})
        remote = load(directory/'remote.json')
        peaks = peak_per_seller(remote['attempts'])
        ops.append(dict(run=directory.name, mode=settings['mode'], scenario=settings['scenario'],
                        passed=summary['checker']['passed'], normalP99=summary['latency'][0]['p99Ms'],
                        graceful=stopped.get('graceful'), shutdownMs=stopped.get('shutdownMs'), exitCode=stopped.get('exitCode'),
                        extraPartnerCalls=len(remote['attempts']) - len(remote['effects']),
                        peakSellerCalls=max(peaks.values(), default=0), peakSlowSellerCalls=peaks.get('slow', 0),
                        duplicateBusiness=traces(directory, 'duplicate_business')))
    observations = [json.loads(x) for x in (directory/'observations.jsonl').read_text().splitlines()] if (directory/'observations.jsonl').exists() else []
    samples = []
    for item in observations:
        service = item.get('service') or {}
        signal = service.get('inboxOldestPendingMsBySeller')
        truth = (item.get('oldestMs') or {}).get('slow')
        if signal is None or truth is None:
            continue
        samples.append(dict(lag=service.get('committedRemaining'), signal=signal.get('slow', 0), truth=truth))
    if samples:
        sli.append(dict(run=directory.name, mode=settings['mode'], scenario=settings['scenario'], samples=len(samples),
                        peakLag=max((x['lag'] or 0) for x in samples), peakSignalMs=max(x['signal'] for x in samples),
                        peakTruthMs=max(x['truth'] for x in samples),
                        medianAbsErrorMs=statistics.median(abs(x['signal'] - x['truth']) for x in samples)))

(root/'ops.json').write_text(json.dumps(dict(ops=ops, sli=sli), ensure_ascii=False, indent=2))
lines = ['# 운영 시나리오', '', '| run | 구조 | 시나리오 | checker | 정상 p99 ms | 정상 종료 | 종료 ms | 추가 판매처 호출 | 판매처 최대 동시 호출 | 중복 처리 |',
         '| --- | --- | --- | --- | ---: | --- | ---: | ---: | ---: | ---: |']
for x in ops:
    lines.append(f"| {x['run'][-5:]} | {x['mode']} | {x['scenario']} | {'통과' if x['passed'] else '실패'} | {x['normalP99']} | "
                 f"{x['graceful']} | {x['shutdownMs']} | {x['extraPartnerCalls']} | {x['peakSellerCalls']} | {x['duplicateBusiness']} |")
lines += ['', '# inbox 대기 신호와 Kafka lag', '', '| run | 구조 | 시나리오 | 표본 | 최대 lag | 최대 신호 ms | 최대 실제 ms | 오차 중앙값 ms |',
          '| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |']
for x in sli:
    lines.append(f"| {x['run'][-5:]} | {x['mode']} | {x['scenario']} | {x['samples']} | {x['peakLag']} | {x['peakSignalMs']} | {x['peakTruthMs']} | {x['medianAbsErrorMs']:.0f} |")
(root/'OPS.md').write_text('\n'.join(lines) + '\n')
print('\n'.join(lines))
