#!/usr/bin/env python3
"""Checker for one run. Verdicts are kept apart instead of one pass flag:

  safety     no duplicate or unknown effect, payload as planned, order kept, no stale-owner write
  liveness   every accepted operation reached the seller; nothing left claimed or pending
  budget     concurrent calls (global, per seller, per order, same event) and retries per second,
             measured on the seller API's own request log
  latency    per cohort, from source acceptance to the seller's effect (and to the internal record)
  observability  the source's backlog signal and alert against the truth rebuilt from effects
  evidence   the fault was really applied, clocks agreed, the generator kept its schedule

Inputs are the files the runner collects into the run directory; see runner.collect().
"""
import argparse
import bisect
import collections
import json
import math
from pathlib import Path

TARGET_MS = 1500
GRACE_MS = 3000


def load_json(path, default=None):
    return json.loads(path.read_text()) if path.exists() else default


def load_lines(path):
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()] if path.exists() else []


def pct(values, q):
    if not values:
        return None
    values = sorted(values)
    return values[max(0, math.ceil(q * len(values)) - 1)]


def max_overlap(intervals):
    """Highest number of intervals [start, end) open at once, and how long it was above each level."""
    points = []
    for start, end in intervals:
        points.append((start, 1))
        points.append((end, -1))
    points.sort(key=lambda p: (p[0], p[1]))  # an end at t frees its slot before a start at t
    current = peak = 0
    for _, delta in points:
        current += delta
        peak = max(peak, current)
    return peak


def time_above(intervals, limit):
    points = sorted([(s, 1) for s, _ in intervals] + [(e, -1) for _, e in intervals], key=lambda p: (p[0], p[1]))
    current, last, above = 0, None, 0
    for t, delta in points:
        if last is not None and current > limit:
            above += t - last
        current += delta
        last = t
    return above


def max_in_window(times, window_ms):
    times = sorted(times)
    best = 0
    for i, t in enumerate(times):
        j = bisect.bisect_right(times, t + window_ms - 1e-9)
        best = max(best, j - i)
    return best


def partner_traces(root):
    traces = []
    for log in sorted(root.glob('partner-*.log')):
        for line in log.read_text(errors='replace').splitlines():
            if line.startswith('TRACE '):
                try:
                    traces.append(json.loads(line[6:]))
                except ValueError:
                    pass
    return traces


def check(root):
    run = load_json(root/'run.json')
    trace = load_lines(root/'trace.jsonl')
    published = {r['eventId']: r for r in load_lines(root/'load.jsonl')}
    obligations = {r['event_id']: r for r in load_json(root/'obligations.json', [])}
    mock = load_json(root/'mock.json', dict(effects=[], attempts=[]))
    partner_db = load_json(root/'partner-db.json', {})
    samples = load_lines(root/'metrics.jsonl')
    traces = partner_traces(root)
    start = run['startAt']
    end_of_observation = run.get('collectedAt') or max([a.get('end_at') or 0 for a in mock['attempts']] + [start])
    fault_target = run.get('faultSeller', 'slow')
    limits = run['limits']

    planned = {e['event']['eventId']: e for e in trace}
    # Cohorts follow each operation's own planned time. The trace labels an event with the phase
    # its order arrived in, but CHANGE and CANCEL run 2s and 5s later and can fall in the next phase.
    bounds = sorted(((b[0], name) for name, b in (run.get('phaseBounds') or {}).items()), reverse=True)
    if bounds:
        for e in trace:
            e['phase'] = next(name for begin, name in bounds if e['plannedOffsetMs'] >= begin)
    accepted = {k: v for k, v in published.items() if v.get('status') == 200 and v.get('createdAt')}
    created = {k: v['createdAt'] for k, v in accepted.items()}

    # ---- safety
    effects_by_event = collections.defaultdict(list)
    for e in mock['effects']:
        effects_by_event[e['event_id']].append(e)
    duplicates = [k for k, v in effects_by_event.items() if len(v) > 1]
    unknown = [k for k in effects_by_event if k not in planned]
    mismatched = []
    for k, rows in effects_by_event.items():
        if k not in planned:
            continue
        want = planned[k]['event']
        got = json.loads(rows[0]['payload'])
        if any(got.get(f) != want[f] for f in ('sellerId', 'orderId', 'sequence', 'operation', 'quantity', 'price')):
            mismatched.append(k)
    order_breaks = []
    by_order = collections.defaultdict(list)
    for e in sorted(mock['effects'], key=lambda x: x['effect_index']):
        by_order[(e['seller_id'], e['order_id'])].append(e['seq'])
    for key, seqs in by_order.items():
        if seqs != list(range(1, len(seqs) + 1)):
            order_breaks.append(dict(order=list(key), seqs=seqs))
    rejected_out_of_order = [a['event_id'] for a in mock['attempts'] if a.get('status') == 409]
    # A delivery recorded under an older claim after a newer claim of the same event existed.
    claims = collections.defaultdict(list)
    for t in traces:
        if t['stage'] == 'claimed':
            claims[t['eventId']].append((t['time'], t.get('generation'), t['instance']))
    stale_writes = []
    for t in traces:
        if t['stage'] == 'business_commit' and t.get('generation') is not None:
            newer = [c for c in claims.get(t['eventId'], []) if c[1] > t['generation'] and c[0] < t['time']]
            if newer:
                stale_writes.append(dict(eventId=t['eventId'], generation=t['generation'], newer=newer[0][1]))
    safety = dict(passed=not (duplicates or unknown or mismatched or order_breaks or rejected_out_of_order or stale_writes),
                  duplicateEffects=len(duplicates), unknownEffects=len(unknown), payloadMismatches=len(mismatched),
                  orderBreaks=len(order_breaks), outOfOrderRequests=len(rejected_out_of_order), staleOwnerWrites=len(stale_writes),
                  examples=dict(duplicates=duplicates[:5], orderBreaks=order_breaks[:5], staleOwnerWrites=stale_writes[:5]))

    # ---- liveness
    undelivered = [k for k in accepted if k not in effects_by_event]
    unresolved = [k for k in accepted if obligations.get(k, {}).get('resolved_at') is None]
    held_permits = partner_db.get('heldPermits', [])
    pending_delivered = [r for r in partner_db.get('openInbox', []) if r['event_id'] in effects_by_event]
    not_accepted = [k for k in planned if k not in accepted]
    liveness = dict(passed=not (undelivered or held_permits or pending_delivered),
                    planned=len(planned), accepted=len(accepted), notAccepted=len(not_accepted),
                    undelivered=len(undelivered), unresolvedObligations=len(unresolved),
                    openInboxRows=len(partner_db.get('openInbox', [])), heldPermits=len(held_permits),
                    pendingAfterDelivery=len(pending_delivered),
                    undeliveredBySeller=dict(collections.Counter(planned[k]['event']['sellerId'] for k in undelivered)),
                    examples=undelivered[:5])

    # ---- budget, on the seller API's request log
    attempts = mock['attempts']
    interval = lambda a: (a['start_at'], a['end_at'] if a.get('end_at') is not None else end_of_observation)
    by_seller = collections.defaultdict(list)
    by_order_iv = collections.defaultdict(list)
    by_event_iv = collections.defaultdict(list)
    for a in attempts:
        iv = interval(a)
        by_seller[a['seller_id']].append(iv)
        by_order_iv[(a['seller_id'], a['order_id'])].append(iv)
        by_event_iv[a['event_id']].append(iv)
    global_peak = max_overlap([interval(a) for a in attempts])
    seller_peaks = {s: max_overlap(v) for s, v in by_seller.items()}
    order_peak = max((max_overlap(v) for v in by_order_iv.values()), default=0)
    event_peak = max((max_overlap(v) for v in by_event_iv.values()), default=0)
    first_attempt = {}
    retries_by_seller = collections.defaultdict(list)
    for a in sorted(attempts, key=lambda x: (x['start_at'], x['id'])):
        if a['event_id'] in first_attempt:
            retries_by_seller[a['seller_id']].append(a['start_at'])
        else:
            first_attempt[a['event_id']] = a['start_at']
    retry_peaks = {s: max_in_window(v, 1000) for s, v in retries_by_seller.items()}
    seller_over_ms = {s: time_above(v, limits['seller']) for s, v in by_seller.items() if seller_peaks[s] > limits['seller']}
    budget = dict(passed=global_peak <= limits['global'] and max(seller_peaks.values(), default=0) <= limits['seller']
                  and order_peak <= 1 and max(retry_peaks.values(), default=0) <= limits['retryPerSecond'],
                  limits=limits, globalPeak=global_peak, sellerPeak=max(seller_peaks.values(), default=0),
                  sellerPeaks=seller_peaks, sellerOverLimitMs=seller_over_ms, orderPeak=order_peak, sameEventPeak=event_peak,
                  retryPerSecondPeak=max(retry_peaks.values(), default=0), retryPerSecondPeaks=retry_peaks,
                  requests=len(attempts), extraRequests=len(attempts) - len(first_attempt),
                  requestsByInstance=dict(collections.Counter(a.get('instance') for a in attempts)))

    # ---- latency per cohort (planned phase); deadline misses count every accepted event
    effect_at = {k: v[0]['effect_at'] for k, v in effects_by_event.items()}
    cohorts = collections.defaultdict(lambda: collections.defaultdict(list))
    for k, created_at in created.items():
        p = planned[k]
        group = 'fault' if p['event']['sellerId'] == fault_target else 'normal'
        cohorts[p['phase']][group].append(k)
    latency = {}
    for phase, groups in cohorts.items():
        latency[phase] = {}
        for group, keys in groups.items():
            delivered = [effect_at[k] - created[k] for k in keys if k in effect_at]
            internal = [obligations[k]['completed_at'] - created[k] for k in keys
                        if obligations.get(k, {}).get('completed_at') is not None]
            missing = [k for k in keys if k not in effect_at]
            misses = sum(1 for k in keys if (effect_at.get(k, math.inf) - created[k]) > TARGET_MS)
            internal_misses = sum(1 for k in keys if ((obligations.get(k, {}).get('completed_at') or math.inf) - created[k]) > TARGET_MS)
            accept_delay = [created[k] - accepted[k]['plannedAt'] for k in keys]
            latency[phase][group] = dict(n=len(keys), delivered=len(delivered), censored=len(missing),
                                         p50Ms=pct(delivered, .5), p95Ms=pct(delivered, .95), p99Ms=pct(delivered, .99),
                                         maxMs=max(delivered, default=None), missRate=round(misses / len(keys), 5) if keys else None,
                                         misses=misses, internalP99Ms=pct(internal, .99),
                                         internalMissRate=round(internal_misses / len(keys), 5) if keys else None,
                                         acceptDelayP99Ms=pct(accept_delay, .99))
    # Per seller misses in the fault and recovery phases, so a small seller's damage is not averaged away.
    per_seller = collections.defaultdict(lambda: [0, 0])
    for k, created_at in created.items():
        p = planned[k]
        if p['phase'] in run.get('judgedPhases', []):
            per_seller[p['event']['sellerId']][0] += 1
            if effect_at.get(k, math.inf) - created_at > TARGET_MS:
                per_seller[p['event']['sellerId']][1] += 1
    latency['_perSellerJudged'] = {s: dict(n=n, misses=m) for s, (n, m) in sorted(per_seller.items())}
    judged = [latency.get(ph, {}).get('normal', {}) for ph in run.get('judgedPhases', [])]
    latency_pass = all(j and j['missRate'] is not None and j['missRate'] <= 0.01 for j in judged) if judged else None

    # ---- observability: rebuild the truth at each sample time from acceptance and effect times
    observability = None
    source_samples = [s for s in samples if s.get('source')]
    if source_samples:
        order = sorted(created.items(), key=lambda kv: kv[1])
        created_sorted = [c for _, c in order]
        rows = []
        for s in source_samples:
            t = s['time']
            n = bisect.bisect_right(created_sorted, t)
            overdue_truth = 0
            oldest_truth = {}
            for k, c in order[:n]:
                done = effect_at.get(k, math.inf)
                if done > t:
                    seller = planned[k]['event']['sellerId']
                    oldest_truth.setdefault(seller, t - c)
                    if c <= t - TARGET_MS:
                        overdue_truth += 1
            src = s['source']
            ages = {seller: v['oldestAgeMs'] for seller, v in src.get('sellers', {}).items()}
            errors = [abs(ages.get(seller, 0) - age) for seller, age in oldest_truth.items()]
            rows.append(dict(t=t, truth=overdue_truth > 0, state=s.get('alert'), errors=errors,
                             stale=s.get('alert') == 'UNKNOWN'))
        missed_ms = false_ms = 0
        episode_start = None
        detections = []
        for i, r in enumerate(rows):
            if r['truth'] and episode_start is None:
                episode_start = r['t']
                detected = None
            if r['truth'] and r['state'] == 'ALERT' and episode_start is not None and detected is None:
                detected = r['t'] - episode_start
                detections.append(detected)
            if r['truth'] and r['state'] == 'NORMAL' and r['t'] - episode_start > GRACE_MS:
                missed_ms += 1000
            if not r['truth']:
                if r['state'] == 'ALERT':
                    false_ms += 1000
                episode_start = None
        errors = [e for r in rows for e in r['errors']]
        observability = dict(samples=len(rows), detectionDelaysMs=detections[:20], detectionMaxMs=max(detections, default=None),
                             missedMs=missed_ms, falseAlertMs=false_ms, unknownSamples=sum(r['stale'] for r in rows),
                             ageErrorP50Ms=pct(errors, .5), ageErrorP95Ms=pct(errors, .95), ageErrorMaxMs=max(errors, default=None),
                             lagPeak=max((s.get('lag') or 0 for s in samples), default=None),
                             passed=missed_ms == 0)

    # ---- evidence validity
    late = [v['sentAt'] - v['plannedAt'] for v in published.values()]
    fault = run.get('fault')
    fault_applied = None
    if fault:
        slow_calls = [a for a in attempts if a['seller_id'] == fault_target and fault['from'] <= a['start_at'] < fault['to'] - 3000
                      and a.get('end_at')]
        fault_applied = dict(calls=len(slow_calls), slowCalls=sum(1 for a in slow_calls if a['end_at'] - a['start_at'] >= 2900))
    offsets = run.get('clockOffsetsMs', {})
    evidence = dict(generatorLateP99Ms=pct(late, .99), generatorLateMaxMs=max(late, default=None),
                    loadFailures=sum(1 for v in published.values() if v.get('status') != 200),
                    clockOffsetMaxMs=max((abs(v) for v in offsets.values() if v is not None), default=None),
                    faultApplied=fault_applied)
    evidence['passed'] = ((evidence['clockOffsetMaxMs'] is None or evidence['clockOffsetMaxMs'] <= 5)
                          and (not fault or (fault_applied['slowCalls'] > 0)))

    return dict(safety=safety, liveness=liveness, budget=budget, latency=latency, latencyPassed=latency_pass,
                observability=observability, evidence=evidence)


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('run_dir')
    a = p.parse_args()
    root = Path(a.run_dir)
    result = check(root)
    (root/'checks.json').write_text(json.dumps(result, indent=2))
    print(json.dumps({k: (v.get('passed') if isinstance(v, dict) and 'passed' in v else v)
                      for k, v in result.items() if k != 'latency'}))
