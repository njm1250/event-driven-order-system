#!/usr/bin/env python3
"""Negative controls for check.py: a clean synthetic run passes, and each planted defect fails the
verdict it belongs to. Run before any comparison; a checker that cannot fail proves nothing."""
import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check  # noqa: E402

START = 1_000_000


def clean_run():
    trace, load, obligations, effects, attempts = [], [], [], [], []
    index = 0
    for order in range(1, 4):
        seller = 'slow' if order == 1 else f's0{order}'
        for seq in (1, 2, 3):
            event_id = f'e{order}-{seq}'
            planned = START + order * 100 + seq * 1000
            event = dict(eventId=event_id, sellerId=seller, orderId=order, sequence=seq,
                         operation=['CREATE', 'CHANGE', 'CANCEL'][seq - 1], quantity=seq + 1, price=100.0 + seq)
            trace.append(dict(plannedOffsetMs=planned - START, phase='fault', event=event, routingKey=f'{seller}:{order}'))
            load.append(dict(eventId=event_id, sellerId=seller, plannedAt=planned, sentAt=planned + 1, doneAt=planned + 5,
                             status=200, createdAt=planned + 3))
            obligations.append(dict(event_id=event_id, seller_id=seller, created_at=planned + 3, resolved_at=planned + 200,
                                    completed_at=planned + 150))
            index += 1
            attempts.append(dict(id=index, event_id=event_id, seller_id=seller, order_id=order, seq=seq,
                                 start_at=planned + 50, end_at=planned + 100, status=200, instance='a'))
            effects.append(dict(effect_index=index, event_id=event_id, seller_id=seller, order_id=order, seq=seq,
                                effect_at=planned + 90, payload=json.dumps(event)))
    run = dict(startAt=START, collectedAt=START + 10_000, faultSeller='slow', judgedPhases=['fault'],
               limits={'global': 4, 'seller': 2, 'retryPerSecond': 2})
    return dict(run=run, trace=trace, load=load, obligations=obligations, mock=dict(effects=effects, attempts=attempts),
                db=dict(openInbox=[], heldPermits=[]), traces=[])


def write(directory, data):
    (directory/'run.json').write_text(json.dumps(data['run']))
    (directory/'trace.jsonl').write_text('\n'.join(json.dumps(x) for x in data['trace']))
    (directory/'load.jsonl').write_text('\n'.join(json.dumps(x) for x in data['load']))
    (directory/'obligations.json').write_text(json.dumps(data['obligations']))
    (directory/'mock.json').write_text(json.dumps(data['mock']))
    (directory/'partner-db.json').write_text(json.dumps(data['db']))
    (directory/'partner-1.log').write_text('\n'.join('TRACE ' + json.dumps(t) for t in data['traces']))


class CheckerNegativeControls(unittest.TestCase):
    def verdicts(self, mutate=None):
        data = clean_run()
        if mutate:
            mutate(data)
        with tempfile.TemporaryDirectory() as directory:
            write(Path(directory), data)
            return check.check(Path(directory))

    def test_clean_run_passes(self):
        result = self.verdicts()
        for verdict in ('safety', 'liveness', 'budget'):
            self.assertTrue(result[verdict]['passed'], verdict)

    def test_missing_effect_fails_liveness(self):
        result = self.verdicts(lambda d: d['mock']['effects'].pop())
        self.assertFalse(result['liveness']['passed'])

    def test_duplicate_effect_fails_safety(self):
        def plant(d):
            extra = copy.deepcopy(d['mock']['effects'][0])
            extra['effect_index'] = 99
            d['mock']['effects'].append(extra)
        self.assertFalse(self.verdicts(plant)['safety']['passed'])

    def test_order_inversion_fails_safety(self):
        def plant(d):
            effects = d['mock']['effects']
            effects[0]['effect_index'], effects[1]['effect_index'] = effects[1]['effect_index'], effects[0]['effect_index']
        self.assertFalse(self.verdicts(plant)['safety']['passed'])

    def test_payload_mismatch_fails_safety(self):
        def plant(d):
            payload = json.loads(d['mock']['effects'][0]['payload'])
            payload['quantity'] = 999
            d['mock']['effects'][0]['payload'] = json.dumps(payload)
        self.assertFalse(self.verdicts(plant)['safety']['passed'])

    def test_same_event_called_twice_at_once_fails_budget(self):
        def plant(d):
            extra = dict(d['mock']['attempts'][0], id=99)
            d['mock']['attempts'].append(extra)
        result = self.verdicts(plant)
        self.assertEqual(result['budget']['sameEventPeak'], 2)
        self.assertFalse(result['budget']['passed'])

    def test_seller_over_its_limit_fails_budget(self):
        def plant(d):
            base = d['mock']['attempts'][3]
            for i in range(3):
                d['mock']['attempts'].append(dict(base, id=100 + i, event_id=f'x{i}', order_id=50 + i))
        self.assertFalse(self.verdicts(plant)['budget']['passed'])

    def test_unended_request_counts_as_running(self):
        def plant(d):
            base = d['mock']['attempts'][3]
            d['mock']['attempts'].append(dict(base, id=100, event_id='y1', order_id=60, start_at=base['start_at'] - 10, end_at=None))
            d['mock']['attempts'].append(dict(base, id=101, event_id='y2', order_id=61, start_at=base['start_at'] - 5, end_at=None))
        self.assertGreater(self.verdicts(plant)['budget']['sellerPeak'], 2)

    def test_retry_rate_over_budget_fails_budget(self):
        def plant(d):
            base = d['mock']['attempts'][3]
            for i in range(3):
                d['mock']['attempts'].append(dict(base, id=200 + i, start_at=base['end_at'] + 10 + i * 100,
                                                  end_at=base['end_at'] + 60 + i * 100))
        self.assertFalse(self.verdicts(plant)['budget']['passed'])

    def test_stale_owner_write_fails_safety(self):
        def plant(d):
            d['traces'] = [dict(stage='claimed', eventId='e2-1', generation=1, instance='a', time=START + 10),
                           dict(stage='claimed', eventId='e2-1', generation=2, instance='b', time=START + 20),
                           dict(stage='business_commit', eventId='e2-1', generation=1, instance='a', time=START + 30)]
        self.assertFalse(self.verdicts(plant)['safety']['passed'])

    def test_orphan_permit_fails_liveness(self):
        self.assertFalse(self.verdicts(lambda d: d['db']['heldPermits'].append(dict(seller_id='s02', slot=1)))['liveness']['passed'])

    def test_pending_row_of_delivered_event_fails_liveness(self):
        self.assertFalse(self.verdicts(lambda d: d['db']['openInbox'].append(dict(event_id='e2-1')))['liveness']['passed'])

    def test_late_delivery_is_a_miss_and_missing_one_too(self):
        def plant(d):
            d['mock']['effects'][3]['effect_at'] += 5000
            d['mock']['effects'].pop(4)
        normal = self.verdicts(plant)['latency']['fault']['normal']
        self.assertEqual(normal['misses'], 2)
        self.assertEqual(normal['censored'], 1)


if __name__ == '__main__':
    unittest.main(verbosity=1)
