#!/usr/bin/env python3
"""Separate external system: durable SQLite effects, deterministic faults and idempotency contract.

Each decision (is this a duplicate, is the sequence next, does the fault policy reject it) is made
under one short lock against in-memory state, so concurrent requests see a single order. The
records behind it go to one writer thread that commits them in groups; a request is answered only
after the group holding its records is committed, so a success response always means a durable
effect. Grouping keeps the commit cost from serializing every request behind one fsync.
"""
import argparse
import collections
import json
import os
import queue
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

LEDGER_SCHEMA = '''PRAGMA journal_mode=WAL;
    PRAGMA synchronous=FULL;
    CREATE TABLE IF NOT EXISTS effects(effect_index INTEGER PRIMARY KEY,event_id TEXT,seller_id TEXT,order_id INTEGER,seq INTEGER,operation TEXT,quantity INTEGER,price REAL,occurred_at INTEGER,effect_at INTEGER,payload TEXT);
    CREATE TABLE IF NOT EXISTS attempts(id INTEGER PRIMARY KEY,event_id TEXT,seller_id TEXT,order_id INTEGER,seq INTEGER,start_at INTEGER,end_at INTEGER,status INTEGER,response_lost INTEGER,instance TEXT);
    CREATE TABLE IF NOT EXISTS orders(seller_id TEXT,order_id INTEGER,seq INTEGER,operation TEXT,quantity INTEGER,price REAL,PRIMARY KEY(seller_id,order_id));'''


def now():
    return int(time.time() * 1000)


class Ledger:
    """In-memory state for decisions plus a group-committing writer for durability."""

    def __init__(self, path):
        self.path = path
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript(LEDGER_SCHEMA)
        self.lock = threading.Lock()
        self.effects = {row['event_id']: row['payload'] for row in self.db.execute('SELECT event_id,payload FROM effects')}
        self.orders = {(r['seller_id'], r['order_id']): r['seq'] for r in self.db.execute('SELECT seller_id,order_id,seq FROM orders')}
        self.attempt_counts = collections.Counter(r['event_id'] for r in self.db.execute('SELECT event_id FROM attempts'))
        self.next_attempt = (self.db.execute('SELECT MAX(id) FROM attempts').fetchone()[0] or 0) + 1
        self.next_effect = (self.db.execute('SELECT MAX(effect_index) FROM effects').fetchone()[0] or 0) + 1
        self.active = collections.Counter()
        self.pending = queue.Queue()
        self.writer = threading.Thread(target=self.write_loop, daemon=True)
        self.writer.start()

    def write_loop(self):
        while True:
            batch = [self.pending.get()]
            if batch[0] is None:
                return
            # No waiting window: whatever queued up while the previous commit ran forms this group.
            while len(batch) < 2000:
                try:
                    item = self.pending.get_nowait()
                except queue.Empty:
                    break
                if item is None:
                    self.pending.put(None)
                    break
                batch.append(item)
            try:
                for statements, _ in batch:
                    for sql, params in statements:
                        self.db.execute(sql, params)
                self.db.commit()
                error = None
            except Exception as failure:
                self.db.rollback()
                error = failure
            for _, done in batch:
                done['error'] = error
                done['event'].set()

    def write(self, statements):
        """Blocks until the statements are committed."""
        done = self.enqueue(statements)
        done['event'].wait()
        if done['error']:
            raise done['error']

    def enqueue(self, statements):
        """Queues the statements without waiting; later writes are committed after them."""
        done = dict(event=threading.Event(), error=None)
        self.pending.put((statements, done))
        return done

    def read(self, sql):
        # Own connection: the writer thread keeps using the main one, WAL lets both proceed.
        reader = sqlite3.connect(self.path)
        reader.row_factory = sqlite3.Row
        try:
            return [dict(x) for x in reader.execute(sql)]
        finally:
            reader.close()

    def close(self):
        self.write([])
        self.pending.put(None)
        self.writer.join()
        self.db.close()


ledger = None
ledger_lock = threading.Lock()
controls = {}


class Handler(BaseHTTPRequestHandler):
    # Keep-alive: callers reuse connections instead of a TCP setup per request.
    protocol_version = 'HTTP/1.1'
    # Headers and body go out in separate writes; with Nagle on, the body waits for the caller's
    # delayed ACK (about 40ms) on a kept-alive connection.
    disable_nagle_algorithm = True

    def log_message(self, *args):
        pass

    def body(self):
        return json.loads(self.rfile.read(int(self.headers.get('Content-Length', 0))) or '{}')

    def reply(self, status, value):
        data = json.dumps(value).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        try:
            self.wfile.write(data)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        current = ledger
        if self.path == '/snapshot':
            current.write([])  # everything answered so far is committed
            self.reply(200, dict(effects=current.read('SELECT * FROM effects ORDER BY effect_index'),
                                 attempts=current.read('SELECT * FROM attempts ORDER BY id'),
                                 orders=current.read('SELECT * FROM orders'), active=dict(current.active), time=now()))
        elif self.path == '/stats':
            with current.lock:
                self.reply(200, dict(time=now(), active=dict(current.active), effects=len(current.effects),
                                     attempts=current.next_attempt - 1))
        else:
            self.reply(200, dict(time=now(), active=dict(current.active)))

    def do_POST(self):
        global ledger
        data = self.body()
        if self.path == '/reset':
            # A long-lived server on another host keeps one ledger per experiment run.
            with ledger_lock:
                controls.clear()
                ledger.close()
                ledger = Ledger(os.path.join(ledger_dir, data['name'] + '.sqlite'))
            self.reply(200, dict(reset=True, time=now()))
            return
        if self.path == '/control':
            controls[data['sellerId']] = data
            self.reply(200, dict(accepted=True, time=now()))
            return
        if self.path != '/orders':
            self.reply(404, {})
            return
        current = ledger
        seller = data['sellerId']
        key = self.headers.get('Idempotency-Key')
        if key != data['eventId']:
            self.reply(400, dict(error='event ID must be idempotency key'))
            return
        start = now()
        # A seller without its own policy follows the default one ('*'), e.g. the normal service time.
        policy = controls.get(seller, controls.get('*', {})).copy()
        with current.lock:
            current.active[seller] += 1
            count = current.attempt_counts[key]
            current.attempt_counts[key] += 1
            attempt = current.next_attempt
            current.next_attempt += 1
        # The start record need not be durable before the call is decided; the answer waits for the
        # commit that holds both it and the outcome.
        current.enqueue([('INSERT INTO attempts(id,event_id,seller_id,order_id,seq,start_at,instance) VALUES(?,?,?,?,?,?,?)',
                          (attempt, key, seller, data['orderId'], data['sequence'], start, self.headers.get('X-Instance')))])
        time.sleep(policy.get('delayMs', 0) / 1000)
        status = 200
        statements = []
        with current.lock:
            prior = current.effects.get(key)
            state = current.orders.get((seller, data['orderId']))
            if count < policy.get('failureCount', 0):
                status = 503
            elif prior is not None and policy.get('idempotent', True):
                if json.loads(prior) != data:
                    status = 409
            elif data['sequence'] != (state + 1 if state else 1):
                status = 409
            else:
                payload = json.dumps(data)
                current.effects[key] = payload
                current.orders[(seller, data['orderId'])] = data['sequence']
                index = current.next_effect
                current.next_effect += 1
                statements.append(('INSERT INTO effects(effect_index,event_id,seller_id,order_id,seq,operation,quantity,price,occurred_at,effect_at,payload) VALUES(?,?,?,?,?,?,?,?,?,?,?)',
                                   (index, key, seller, data['orderId'], data['sequence'], data['operation'], data['quantity'], data['price'], data['occurredAt'], now(), payload)))
                statements.append(('INSERT INTO orders VALUES(?,?,?,?,?,?) ON CONFLICT(seller_id,order_id) DO UPDATE SET seq=excluded.seq,operation=excluded.operation,quantity=excluded.quantity,price=excluded.price',
                                   (seller, data['orderId'], data['sequence'], data['operation'], data['quantity'], data['price'])))
            dropped = status == 200 and policy.get('dropResponse', False) and count == 0
            statements.append(('UPDATE attempts SET end_at=?,status=?,response_lost=? WHERE id=?', (now(), status, int(dropped), attempt)))
            current.active[seller] -= 1
        current.write(statements)
        if dropped:
            self.close_connection = True
            self.connection.shutdown(2)
            self.connection.close()
        else:
            self.reply(status, dict(eventId=key, accepted=status == 200, duplicate=prior is not None))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--database', required=True)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8099)
    args = parser.parse_args()
    # Many handler threads wait on one interpreter lock; the default 5ms hand-off interval shows up
    # directly as response delay, so hand it over sooner.
    import sys
    sys.setswitchinterval(0.0005)
    ledger_dir = os.path.dirname(os.path.abspath(args.database))
    ledger = Ledger(args.database)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    server.daemon_threads = True
    server.serve_forever()
