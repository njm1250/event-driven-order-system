#!/usr/bin/env python3
"""Derive diagnostic hypotheses from observations first, then compare controller ground truth."""
import argparse
import collections
import json
import statistics
import hashlib
from pathlib import Path

p=argparse.ArgumentParser();p.add_argument('--evidence',required=True);args=p.parse_args()
root=Path(args.evidence).resolve(); reports=[]
repo=Path(__file__).resolve().parents[1]
if root==repo or repo in root.parents:raise SystemExit('Evidence must be external')
for directory in sorted(root.iterdir()):
    if not (directory/'summary.json').exists():continue
    metrics=json.loads((directory/'summary.json').read_text())
    traces=json.loads((directory/'traces.json').read_text())
    observations=[json.loads(x) for x in (directory/'observations.jsonl').read_text().splitlines()] if (directory/'observations.jsonl').exists() else []
    delayed=collections.defaultdict(list)
    for t in traces:
        if t['stage']=='external_result':delayed[t['sellerId']].append(t['durationMs'])
    delayed={s:sum(v>=500 for v in values) for s,values in delayed.items()}
    completion=[x for x in traces if x['stage']=='business_commit']
    oldest_committed_gap=0
    for o in observations:
        service=o.get('service') or {}
        offset=service.get('brokerCommitted')
        if offset is None and service.get('brokerError'):continue
        if 'logEnd' not in service:continue
        for c in completion:
            if (offset is None or c['offset']>=offset) and c['time']<o['time']:
                oldest_committed_gap=max(oldest_committed_gap,o['time']-c['time'])
    maxwait=metrics['peakDbWaiting']
    proof=dict(missingCommittedSamples=sum(1 for x in observations if x.get('service') and x['service'].get('brokerCommitted') is None and not x['service'].get('brokerError')),delayedCallsBySeller=delayed,peakDbWaiting=maxwait,completedButUncommittedMaxAgeMs=oldest_committed_gap,
               externalP95Ms=metrics['externalP95Ms'],dbAcquireP95Ms=metrics['dbAcquireP95Ms'])
    verdict={}
    verdict['seller_api_delay']='수용' if delayed.get('slow',0)>0 and delayed.get('normal',0)==0 else '기각' if delayed else '보류'
    verdict['shared_db_pool']='수용' if maxwait>0 else '기각' if observations else '보류'
    verdict['post_business_ack_delay']='수용' if oldest_committed_gap>=1500 and not maxwait and not sum(delayed.values()) else '보류' if oldest_committed_gap>=1500 else '기각' if observations else '보류'
    diagnosis=dict(evidence=proof,verdict=verdict)
    # The controller trace is deliberately loaded only AFTER this artifact is written.
    (directory/'diagnosis-before-control.json').write_text(json.dumps(diagnosis,ensure_ascii=False,indent=2))
    controller=json.loads((directory/'controller.json').read_text())
    manifest=json.loads((directory/'manifest.json').read_text())
    injected=next((x for x in controller if x['action'] in {'fault_injected','gate_armed'}),None)
    if injected and injected.get('gate'):
        marker=directory/'hooks'/f'{injected["gate"]}.reached'
        if marker.exists():
            try:injected=dict(injected,reachedAt=int(marker.read_text().splitlines()[1]))
            except (ValueError,IndexError):pass
    detection=json.loads((directory/'detection.json').read_text()) if (directory/'detection.json').exists() else None
    detected_ms=detection['detectedAt']-injected.get('reachedAt',injected['time']) if detection and injected else None
    # Strictly non-causal description: overlapping wait indicators require the control removal evidence.
    analysis_id=hashlib.sha256(directory.name.encode()).hexdigest()[:12]
    lines=[f'# 관측 기반 가설 기록: 실험 {analysis_id}', '',
        '자동 규칙이 수집 자료를 먼저 판정하고 이후 제어기와 대조했다. 사람의 블라인드 RCA나 원인 탐색 시간을 측정한 실험은 아니다.', '',
        '| 가설 | 예상 근거 · 수집 방법 | 실제 관측 | 판정 |', '| --- | --- | --- | --- |',
        f'| 판매처 API 지연 | slow 외부 호출 500ms 이상, normal은 증가하지 않음. external_result trace | {delayed} | {verdict["seller_api_delay"]} |',
        f'| 공용 DB 연결 부족 | pool 대기 발생. 독립 /observe 표본과 transaction 진입 waitMs | 최대 대기 {maxwait}, 획득 p95 {metrics["dbAcquireP95Ms"]}ms | {verdict["shared_db_pool"]} |',
        f'| 업무 완료 후 ack 지연 | 실제 업무 commit trace offset이 broker committed 이상인 채 1500ms 이상 남음 | 최대 {oldest_committed_gap}ms, external p95 {metrics["externalP95Ms"]}ms | {verdict["post_business_ack_delay"]} |','',
        '수용은 주입/제거 대조 범위의 근거다. 동일 시점의 지표 상승만으로 다른 실행의 원인을 증명하지 않는다. API 대기 중 아직 못 읽은 DB 구간의 대기가 없는 것과 업무 경로 전체의 DB 지연이 없는 것은 다르다.', '',
        f'제어기 대조: {json.dumps(injected,ensure_ascii=False)}',
        f'이상 감지 지연: {detected_ms}ms. 자료 저장 지연: {detection["evidenceSavedAt"]-detection["detectedAt"] if detection else None}ms. 미감지는 null이다.',
        f'독립 checker: {metrics["checker"]}', '',
        '다음 확인: 보류된 가설은 offset/업무 commit 경계의 더 촘촘한 표본 (최초 committed가 없는 상태는 null 그대로이며 0으로 대체하지 않음)과 대응 DB/외부 이력을 확인한다. 정상 p99와 복구 시간은 summary.json, 실제 주입 종류/시각은 controller.json에서 검증한다.']
    (directory/'RCA.md').write_text('\n'.join(lines)+'\n')
    reports.append(dict(run=directory.name,settings=manifest['settings'],metrics=metrics,diagnosis=diagnosis,
                        detectionDelayMs=detected_ms,evidenceDelayMs=detection['evidenceSavedAt']-detection['detectedAt'] if detection else None))
(root/'index.json').write_text(json.dumps(reports,ensure_ascii=False,indent=2))
for mode in ['sequential','async','inbox']:
    cases=[x for x in reports if x['settings'].get('mode')==mode and x['settings'].get('scenario')=='api' and x['metrics']['checker']['passed'] and x['settings'].get('observe')]
    # Limit comparisons to the warmed-up committed implementation, not preserved development runs.
    latest=cases[-3:]
    p99=[x['metrics']['latency'][0]['p99Ms'] for x in latest]
    print(mode,'normal p99',p99,'median',statistics.median(p99) if p99 else None)
print('Analyzed',len(reports),'completed runs')
