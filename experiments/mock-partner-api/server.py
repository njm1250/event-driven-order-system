#!/usr/bin/env python3
"""Separate external system: durable SQLite effects, deterministic faults and idempotency contract."""
import argparse
import json
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

lock = threading.RLock()
controls = {}
active = {}

def now():
    return int(time.time() * 1000)

class Handler(BaseHTTPRequestHandler):
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
        if self.path == '/snapshot':
            with lock:
                effects = [dict(x) for x in db.execute('SELECT * FROM effects ORDER BY effect_index')]
                attempts = [dict(x) for x in db.execute('SELECT * FROM attempts ORDER BY id')]
                orders = [dict(x) for x in db.execute('SELECT * FROM orders')]
                self.reply(200, dict(effects=effects, attempts=attempts, orders=orders, active=active.copy(), time=now()))
        else:
            self.reply(200, dict(time=now(), active=active.copy()))

    def do_POST(self):
        data = self.body()
        if self.path == '/control':
            with lock:
                controls[data['sellerId']] = data
            self.reply(200, dict(accepted=True, time=now()))
            return
        if self.path != '/orders':
            self.reply(404, {})
            return
        seller = data['sellerId']
        key = self.headers.get('Idempotency-Key')
        if key != data['eventId']:
            self.reply(400, dict(error='event ID must be idempotency key'))
            return
        start = now()
        with lock:
            policy = controls.get(seller, {}).copy()
            active[seller] = active.get(seller, 0) + 1
            count = db.execute('SELECT COUNT(*) FROM attempts WHERE event_id=?', (key,)).fetchone()[0]
            attempt = db.execute('INSERT INTO attempts(event_id,seller_id,order_id,seq,start_at) VALUES(?,?,?,?,?)',
                                 (key, seller, data['orderId'], data['sequence'], start)).lastrowid
            db.commit()
        time.sleep(policy.get('delayMs', 0) / 1000)
        status = 200
        dropped = False
        with lock:
            prior = db.execute('SELECT * FROM effects WHERE event_id=?', (key,)).fetchone()
            state = db.execute('SELECT * FROM orders WHERE seller_id=? AND order_id=?', (seller, data['orderId'])).fetchone()
            if count < policy.get('failureCount', 0):
                status = 503
            elif prior and policy.get('idempotent', True):
                if json.loads(prior['payload']) != data:
                    status = 409
            elif data['sequence'] != (state['seq'] + 1 if state else 1):
                status = 409
            else:
                db.execute('INSERT INTO effects(event_id,seller_id,order_id,seq,operation,quantity,price,occurred_at,effect_at,payload) VALUES(?,?,?,?,?,?,?,?,?,?)',
                           (key, seller, data['orderId'], data['sequence'], data['operation'], data['quantity'], data['price'], data['occurredAt'], now(), json.dumps(data)))
                db.execute('INSERT INTO orders VALUES(?,?,?,?,?,?) ON CONFLICT(seller_id,order_id) DO UPDATE SET seq=excluded.seq,operation=excluded.operation,quantity=excluded.quantity,price=excluded.price',
                           (seller, data['orderId'], data['sequence'], data['operation'], data['quantity'], data['price']))
            dropped = status == 200 and policy.get('dropResponse', False) and count == 0
            db.execute('UPDATE attempts SET end_at=?,status=?,response_lost=? WHERE id=?', (now(), status, int(dropped), attempt))
            db.commit()
            active[seller] -= 1
        if dropped:
            self.close_connection = True
            self.connection.shutdown(2)
            self.connection.close()
        else:
            self.reply(status, dict(eventId=key, accepted=status == 200, duplicate=bool(prior)))

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--database', required=True)
    parser.add_argument('--host',default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8099)
    args = parser.parse_args()
    db = sqlite3.connect(args.database, check_same_thread=False)
    db.row_factory = sqlite3.Row
    db.executescript('''PRAGMA journal_mode=WAL;
    CREATE TABLE IF NOT EXISTS effects(effect_index INTEGER PRIMARY KEY AUTOINCREMENT,event_id TEXT,seller_id TEXT,order_id INTEGER,seq INTEGER,operation TEXT,quantity INTEGER,price REAL,occurred_at INTEGER,effect_at INTEGER,payload TEXT);
    CREATE TABLE IF NOT EXISTS attempts(id INTEGER PRIMARY KEY AUTOINCREMENT,event_id TEXT,seller_id TEXT,order_id INTEGER,seq INTEGER,start_at INTEGER,end_at INTEGER,status INTEGER,response_lost INTEGER);
    CREATE TABLE IF NOT EXISTS orders(seller_id TEXT,order_id INTEGER,seq INTEGER,operation TEXT,quantity INTEGER,price REAL,PRIMARY KEY(seller_id,order_id));''')
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()
