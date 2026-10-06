#!/usr/bin/env python3
"""Real MySQL legacy schema/data migration check; only creates/removes explicitly named scratch DBs."""
import argparse
import json
import subprocess
import sys
from pathlib import Path
from run import REPO, COMPOSE, command, sql, save
p=argparse.ArgumentParser();p.add_argument('--evidence',required=True);a=p.parse_args();root=Path(a.evidence).resolve()
if root==REPO or REPO in root.parents:raise SystemExit('Evidence must be external')
root.mkdir(parents=True,exist_ok=True)
order_db='migration_check_order';inventory_db='migration_check_inventory'
try:
    command(COMPOSE+['exec','-T','mysql','mysql','-uroot','-plabpassword','-e',f'CREATE DATABASE {order_db}; CREATE DATABASE {inventory_db};'],stderr=subprocess.DEVNULL)
    sql('''CREATE TABLE orders(order_id BIGINT PRIMARY KEY AUTO_INCREMENT,product_cd VARCHAR(255),quantity INT NOT NULL,price DOUBLE NOT NULL,order_status ENUM('PENDING','CONFIRMED','CANCELLED','SHIPPED'),created_at DATETIME(6),updated_at DATETIME(6));
    INSERT INTO orders(product_cd,quantity,price,order_status,created_at,updated_at) VALUES('LEGACY',2,100,'CONFIRMED',NOW(6),NOW(6));
    CREATE TABLE outbox_event(id BIGINT PRIMARY KEY AUTO_INCREMENT,event_id VARCHAR(36) NOT NULL,aggregate_id VARCHAR(255) NOT NULL,topic VARCHAR(255) NOT NULL,event_type VARCHAR(255) NOT NULL,payload TEXT NOT NULL,status ENUM('PENDING','SENT') NOT NULL,created_at DATETIME(6),sent_at DATETIME(6));
    INSERT INTO outbox_event(event_id,aggregate_id,topic,event_type,payload,status) VALUES('legacy-event','1','inventory-order-created','legacy','{}','SENT');''',order_db)
    sql('''CREATE TABLE inventories(inventory_id BIGINT PRIMARY KEY AUTO_INCREMENT,product_cd VARCHAR(255),stock_quantity INT NOT NULL,version INT);
    INSERT INTO inventories(product_cd,stock_quantity,version) VALUES('LEGACY',98,0);
    CREATE TABLE stock_history(id BIGINT PRIMARY KEY AUTO_INCREMENT,event_id VARCHAR(36) NOT NULL,order_id BIGINT NOT NULL,delta INT NOT NULL,created_at DATETIME(6));
    INSERT INTO stock_history(event_id,order_id,delta,created_at) VALUES('legacy-event',1,-2,NOW(6));''',inventory_db)
    for repetition in [1,2]:
        subprocess.run([sys.executable,str(REPO/'experiments/migrate.py'),'--evidence',str(root/f'apply-{repetition}'),'--order-db',order_db,'--inventory-db',inventory_db],check=True)
    orders=sql('SELECT * FROM orders',order_db);history=sql('SELECT * FROM stock_history',inventory_db)
    assert orders[0]['seller_id']=='legacy' and orders[0]['run_id']=='api' and orders[0]['quantity']==2 and orders[0]['price']==100
    assert len(history)==1 and history[0]['delta']==-2
    assert sql('SELECT COUNT(*) n FROM outbox_event',order_db)[0]['n']=='1'
    save(root/'migration-check.json',dict(passed=True,orders=orders,history=history,
        orderIndexes=sql('SHOW INDEX FROM outbox_event',order_db),stockIndexes=sql('SHOW INDEX FROM stock_history',inventory_db),repeatApplications=2))
    print('Legacy MySQL data preserved, UNIQUE applied, second application safe')
finally:
    command(COMPOSE+['exec','-T','mysql','mysql','-uroot','-plabpassword','-e',f'DROP DATABASE IF EXISTS {order_db}; DROP DATABASE IF EXISTS {inventory_db};'],stderr=subprocess.DEVNULL)
