#!/usr/bin/env python3
"""Representative comparison for the single AWS session. Repetition and failure boundaries stay local."""
import argparse
import json
import subprocess
import sys
from pathlib import Path
from evidence_paths import validate_evidence_path
from run import REPO, run_case, PROCESSES, HANDLES, stop

p = argparse.ArgumentParser(); p.add_argument('--evidence', required=True); a = p.parse_args()
root = Path(a.evidence).resolve()
validate_evidence_path(root)
root.mkdir(parents=True, exist_ok=True)
plan = [(mode, 'api', repeat) for repeat in range(1, 4)
        for mode in ['sequential', 'async', 'parallel-consumer', 'retry-topic', 'inbox']]
plan += [(mode, 'hang', repeat) for repeat in range(1, 4) for mode in ['retry-topic', 'inbox']]
failures = []
try:
    for mode, scenario, repeat in plan:
        try:
            run_case(root, mode, scenario, repeat, workload='market', partitions=4)
        except Exception as error:
            # Keep going: one failed run must not cost the rest of a paid session.
            failures.append(dict(mode=mode, scenario=scenario, repeat=repeat, error=repr(error)))
finally:
    for process in PROCESSES: stop(process)
    for handle in HANDLES: handle.close()
(root/'aws-failures.json').write_text(json.dumps(failures, indent=2))
subprocess.run([sys.executable, str(REPO/'experiments/analyze.py'), '--evidence', str(root)], check=True)
subprocess.run([sys.executable, str(REPO/'experiments/comparison_report.py'), '--evidence', str(root)], check=True)
print(json.dumps(dict(planned=len(plan), failed=len(failures))))
