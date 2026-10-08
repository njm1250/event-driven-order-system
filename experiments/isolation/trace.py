#!/usr/bin/env python3
"""Planned workload for one run, written before the run starts.

Orders arrive as a Poisson process; each order is CREATE at its arrival, CHANGE 2s later and CANCEL
5s later, without waiting for the earlier operation to be delivered. One large seller ('slow', the
fault target) has 20% of the orders and 19 others share the rest. Every random choice has its own
seed stream, so changing the arrival seed does not also move the seller or routing choices.

The business ordering key is sellerId:orderId. The Kafka routing key depends on the candidate:
'order' keeps that key, 'bucket' sends each order of a seller to one of a fixed number of buckets
(stable CRC32 of the orderId, the same in every language and process).
"""
import argparse
import json
import random
import uuid
import zlib
from pathlib import Path

BIG_SELLER = 'slow'
OTHER_SELLERS = [f's{i:02d}' for i in range(1, 20)]
OFFSETS_MS = [0, 2000, 5000]
OPERATIONS = ['CREATE', 'CHANGE', 'CANCEL']
EVENT_BYTES = 1024


def routing_key(policy, seller, order_id, buckets=2):
    if policy == 'bucket':
        return f'{seller}:{zlib.crc32(str(order_id).encode()) % buckets}'
    return f'{seller}:{order_id}'


def padded(event):
    """Pads the event so its JSON is EVENT_BYTES long; the field order matches the Java record."""
    event['padding'] = ''
    size = len(json.dumps(event, separators=(',', ':')).encode())
    event['padding'] = 'x' * max(0, EVENT_BYTES - size)
    return event


def build(seed, phases, big_share=0.2, run_id='run'):
    """phases: [(name, seconds, events_per_second)]. Returns events sorted by planned offset."""
    arrivals = random.Random(f'{seed}/arrival')
    sellers = random.Random(f'{seed}/seller')
    events = []
    start = 0.0
    order_id = 0
    for name, seconds, rate in phases:
        end = start + seconds
        t = start
        orders_per_second = rate / 3.0
        while orders_per_second > 0:
            t += arrivals.expovariate(orders_per_second)
            if t >= end:
                break
            order_id += 1
            seller = BIG_SELLER if sellers.random() < big_share else sellers.choice(OTHER_SELLERS)
            for seq in (1, 2, 3):
                events.append(dict(
                    plannedOffsetMs=int(t * 1000) + OFFSETS_MS[seq - 1], phase=name,
                    event=padded(dict(eventId=str(uuid.uuid5(uuid.NAMESPACE_URL, f'{seed}/{order_id}/{seq}')),
                                      runId=run_id[-64:], sellerId=seller, orderId=order_id, sequence=seq,
                                      operation=OPERATIONS[seq - 1], occurredAt=1, schemaVersion=1,
                                      quantity=seq + 1, price=float(100 + seq)))))
        start = end
    events.sort(key=lambda e: (e['plannedOffsetMs'], e['event']['orderId'], e['event']['sequence']))
    return events


def phase_bounds(phases):
    """Planned offsets (ms) where each phase starts and ends."""
    bounds, start = {}, 0
    for name, seconds, _ in phases:
        bounds[name] = (start * 1000, (start + seconds) * 1000)
        start += seconds
    return bounds


def write(path, events, policy):
    with Path(path).open('w') as out:
        for e in events:
            out.write(json.dumps(dict(e, routingKey=routing_key(policy, e['event']['sellerId'], e['event']['orderId'])),
                                 separators=(',', ':')) + '\n')


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--seed', type=int, required=True)
    p.add_argument('--phases', required=True, help='name:seconds:rate,...')
    p.add_argument('--policy', choices=['order', 'bucket'], default='order')
    p.add_argument('--run-id', default='run')
    p.add_argument('--out', required=True)
    a = p.parse_args()
    phases = [(n, float(s), float(r)) for n, s, r in (x.split(':') for x in a.phases.split(','))]
    events = build(a.seed, phases, run_id=a.run_id)
    write(a.out, events, a.policy)
    print(json.dumps(dict(events=len(events), sellers=len({e['event']['sellerId'] for e in events}))))
