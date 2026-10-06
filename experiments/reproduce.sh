#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
evidence_dir="${KAFKA_EVIDENCE_DIR:-$HOME/Desktop/experiment-evidence/$(date +%Y-%m-%d)}"
mkdir -p "$evidence_dir"
cd "$repo_dir"
cleanup() { docker compose -p partner-isolation -f docker-compose.experiment.yml down > "$evidence_dir/cleanup.log" 2>&1; }
trap cleanup EXIT INT TERM
./gradlew test bootJar --no-daemon > "$evidence_dir/build.log" 2>&1
python3 -m unittest discover -s experiments -p 'test_*.py' > "$evidence_dir/checker-unit.log" 2>&1
docker compose -p partner-isolation -f docker-compose.experiment.yml up -d --wait
python3 experiments/run.py --baseline --evidence "$evidence_dir"
python3 experiments/verify_migration.py --evidence "$evidence_dir/migration-verification"
python3 experiments/run.py --suite --evidence "$evidence_dir" | tee "$evidence_dir/suite.log"
for mode in sequential async inbox; do
  python3 experiments/end_to_end.py --mode "$mode" --evidence "$evidence_dir"
  python3 experiments/end_to_end.py --mode "$mode" --seller-fault --evidence "$evidence_dir"
done
for repetition in 1 2 3; do
  for polling in 1000 100; do
    python3 experiments/end_to_end.py --mode inbox --poll-ms "$polling" --evidence "$evidence_dir"
  done
done
for boundary in broker_ack inventory_broker_ack; do
  for repetition in 1 2; do
    python3 experiments/end_to_end.py --boundary "$boundary" --evidence "$evidence_dir"
  done
done
python3 experiments/end_to_end.py --concurrent-duplicate --evidence "$evidence_dir"
python3 experiments/admission.py --evidence "$evidence_dir"
python3 experiments/extra_cases.py --evidence "$evidence_dir"
python3 experiments/analyze.py --evidence "$evidence_dir"
python3 experiments/audit_local.py --evidence "$evidence_dir"
