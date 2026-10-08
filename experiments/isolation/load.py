#!/usr/bin/env python3
"""Load generator for one run; runs on its own host so publishing does not cost the measured hosts.

Each planned event is sent at start + plannedOffsetMs, whether or not earlier requests have
finished. A request that cannot start on time is still sent, late, and its lateness is recorded:
planned times are never shifted, so a slow target shows up as delay instead of a lower input rate.

target=source: POST /experiment/obligations (the source accepts it in its own transaction).
target=mock:   POST /orders directly to the partner API mock, to measure the mock alone.
Standard library only, so the host needs nothing but python3.
"""
import argparse
import http.client
import json
import queue
import threading
import time
from urllib.parse import urlparse


def now_ms():
    return time.time() * 1000


class Sender(threading.Thread):
    def __init__(self, url, target, work, results):
        super().__init__(daemon=True)
        parsed = urlparse(url)
        self.host, self.port = parsed.hostname, parsed.port or 80
        self.target, self.work, self.results = target, work, results
        self.connection = None

    def connect(self):
        self.connection = http.client.HTTPConnection(self.host, self.port, timeout=30)

    def post(self, path, body, headers):
        if self.connection is None:
            self.connect()
        try:
            self.connection.request('POST', path, body=body, headers=headers)
            response = self.connection.getresponse()
            return response.status, response.read()
        except Exception:
            self.connection.close()
            self.connection = None
            raise

    def run(self):
        while True:
            item = self.work.get()
            if item is None:
                return
            planned, entry = item
            event = entry['event']
            record = dict(eventId=event['eventId'], sellerId=event['sellerId'], plannedAt=planned, attempts=0)
            record['sentAt'] = now_ms()
            for attempt in range(1, 6):
                record['attempts'] = attempt
                try:
                    if self.target == 'source':
                        body = json.dumps(dict(event=event, routingKey=entry['routingKey']))
                        status, data = self.post('/experiment/obligations', body, {'Content-Type': 'application/json'})
                        if status == 200:
                            record['createdAt'] = json.loads(data)['createdAt']
                    else:
                        body = json.dumps(dict(event, occurredAt=int(planned)))
                        status, data = self.post('/orders', body, {'Content-Type': 'application/json',
                                                                   'Idempotency-Key': event['eventId'], 'X-Instance': 'load'})
                    record['status'] = status
                    if status == 200:
                        break
                except Exception as error:
                    record['status'] = None
                    record['error'] = repr(error)
                # A lost response is not a rejection: the same eventId is sent again and accepted once.
                time.sleep(0.1)
            record['doneAt'] = now_ms()
            self.results.append(record)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--trace', required=True)
    p.add_argument('--target', choices=['source', 'mock'], required=True)
    p.add_argument('--url', required=True)
    p.add_argument('--start-at', type=float, required=True, help='epoch ms of planned offset 0')
    p.add_argument('--out', required=True)
    p.add_argument('--threads', type=int, default=64)
    a = p.parse_args()
    entries = [json.loads(line) for line in open(a.trace)]
    work, results = queue.Queue(), []
    senders = [Sender(a.url, a.target, work, results) for _ in range(a.threads)]
    for s in senders:
        s.start()
    queue_depth = 0
    for entry in entries:
        planned = a.start_at + entry['plannedOffsetMs']
        wait = (planned - now_ms()) / 1000
        if wait > 0:
            time.sleep(wait)
        work.put((planned, entry))
        queue_depth = max(queue_depth, work.qsize())
    for _ in senders:
        work.put(None)
    for s in senders:
        s.join()
    with open(a.out, 'w') as out:
        for record in sorted(results, key=lambda r: r['plannedAt']):
            out.write(json.dumps(record, separators=(',', ':')) + '\n')
    late = sorted(r['sentAt'] - r['plannedAt'] for r in results)
    failed = sum(1 for r in results if r.get('status') != 200)
    print(json.dumps(dict(events=len(results), failed=failed, maxQueue=queue_depth,
                          lateP99Ms=round(late[int(len(late) * 0.99) - 1], 2) if late else None,
                          lateMaxMs=round(late[-1], 2) if late else None)))


if __name__ == '__main__':
    main()
