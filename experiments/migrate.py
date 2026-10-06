#!/usr/bin/env python3
"""Apply explicit schema changes without discarding existing source data. Duplicates fail closed."""
import argparse
import json
import subprocess
from pathlib import Path

REPO=Path(__file__).resolve().parents[1]
p=argparse.ArgumentParser()
p.add_argument('--project',default='partner-isolation')
p.add_argument('--compose',default=str(REPO/'docker-compose.experiment.yml'))
p.add_argument('--evidence',required=True)
a=p.parse_args()
root=Path(a.evidence).resolve()
if root==REPO or REPO in root.parents:raise SystemExit('Evidence must be external')
root.mkdir(parents=True,exist_ok=True)
commands=[]
prefix=['docker','compose','-p',a.project,'-f',a.compose,'exec','-T','mysql','mysql','-uroot','-plabpassword','--batch']
def query(database,statement):
    commands.append(dict(database=database,sql=statement))
    (root/'migration-executed.json').write_text(json.dumps(commands,indent=2))
    return subprocess.check_output(prefix+[database,'-e',statement],text=True,stderr=subprocess.DEVNULL)
# Create missing tables first. Existing tables are changed explicitly below.
subprocess.run(prefix, input=(REPO/'experiments/migrations/001-source-schema.sql').read_text(),text=True,check=True,stderr=subprocess.DEVNULL)
existing=query('order_db','SHOW COLUMNS FROM orders')
for column,definition in [('seller_id',"VARCHAR(255) NOT NULL DEFAULT 'legacy'"),('run_id',"VARCHAR(255) DEFAULT 'api'"),('partner_sequence','INT NOT NULL DEFAULT 0'),('version','BIGINT')]:
    if column+'\t' not in existing:query('order_db',f'ALTER TABLE orders ADD COLUMN {column} {definition}')
for database,table in [('order_db','outbox_event'),('inventory_db','stock_history'),('inventory_db','outbox_event')]:
    duplicates=query(database,f'SELECT event_id,COUNT(*) n FROM {table} GROUP BY event_id HAVING n>1')
    if len(duplicates.splitlines())>1:raise SystemExit(f'{database}.{table}: existing duplicate IDs need review, migration refused')
    indexes=query(database,f'SHOW INDEX FROM {table}')
    unique_event=any('\t0\t' in line and '\tevent_id\t' in line for line in indexes.splitlines()[1:])
    if not unique_event:query(database,f'ALTER TABLE {table} ADD CONSTRAINT uq_{table}_event UNIQUE(event_id)')
print('Explicit schema migration completed')
