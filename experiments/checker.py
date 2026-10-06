#!/usr/bin/env python3
"""Independent effect checker; no listener's success counters are trusted."""
import argparse
import collections
import json
from pathlib import Path

def check(expected, remote, db_effects, db_orders):
    errors = []
    expected_by_id = {e['eventId']: e for e in expected}
    if len(expected_by_id) != len(expected):
        errors.append('expected input contains duplicate event IDs')
    effects = remote['effects']
    count = collections.Counter(e['event_id'] for e in effects)
    for event_id in expected_by_id:
        if count[event_id] != 1:
            errors.append(f'external effect count {event_id}: {count[event_id]}')
    for event_id in count.keys() - expected_by_id.keys():
        errors.append(f'unexpected external effect {event_id}')
    grouped = collections.defaultdict(list)
    latest = {}
    for e in expected:
        key = (e['sellerId'], e['orderId'])
        if key not in latest or latest[key]['sequence'] < e['sequence']:
            latest[key] = e
    for row in effects:
        key = (row['seller_id'], row['order_id'])
        grouped[key].append(row['seq'])
        event = expected_by_id.get(row['event_id'])
        if event:
            for out, source in [('seller_id','sellerId'), ('order_id','orderId'), ('run_id','runId'), ('seq','sequence'), ('operation','operation'), ('quantity','quantity'), ('price','price')]:
                if row[out] != event[source]:
                    errors.append(f'external payload mismatch {row["event_id"]}/{out}')
    for key, sequence in grouped.items():
        wanted = sorted(e['sequence'] for e in expected if (e['sellerId'],e['orderId']) == key)
        if sequence != wanted:
            errors.append(f'external order violation {key}: {sequence} expected {wanted}')
    internal = collections.Counter(e['event_id'] for e in db_effects)
    if internal != collections.Counter(expected_by_id.keys()):
        errors.append('business DB event IDs differ from expected')
    for row in db_effects:
        event = expected_by_id.get(row['event_id'])
        if event:
            for out, source in [('seller_id','sellerId'), ('order_id','orderId'), ('run_id','runId'), ('seq','sequence'), ('operation','operation'), ('quantity','quantity'), ('price','price')]:
                if row[out] != event[source]:
                    errors.append(f'business DB payload mismatch {row["event_id"]}/{out}')
    for label, orders in [('external',remote['orders']), ('business DB',db_orders)]:
        actual = {(row['seller_id'],row['order_id']):row for row in orders}
        if actual.keys() != latest.keys():
            errors.append(f'{label} final order keys differ')
        for key, event in latest.items():
            if key in actual:
                for out, source in [('seq','sequence'), ('operation','operation'), ('quantity','quantity'), ('price','price')]:
                    if actual[key][out] != event[source]:
                        errors.append(f'{label} final state mismatch {key}/{out}')
    if any(x['status'] == 409 for x in remote['attempts']):
        errors.append('external API rejected an out-of-order or conflicting request')
    return dict(passed=not errors, expected=len(expected), externalEffects=len(effects), dbEffects=len(db_effects),
                requests=len(remote['attempts']), responseLost=sum(x['response_lost'] or 0 for x in remote['attempts']), errors=errors)

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('directory')
    args = parser.parse_args()
    path = Path(args.directory)
    result = check(json.loads((path/'expected.json').read_text()), json.loads((path/'remote.json').read_text()),
                   json.loads((path/'db-effects.json').read_text()), json.loads((path/'db-orders.json').read_text()))
    print(json.dumps(result, ensure_ascii=False, indent=2))
    raise SystemExit(0 if result['passed'] else 1)
