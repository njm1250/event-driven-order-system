#!/usr/bin/env python3
"""Runs on the controller host of the AWS cluster.

Stage 1 (one broker): every consumer structure, the representative comparison and a throughput sweep.
Stage 2 (three brokers, RF 3, min ISR 2): a subset that checks whether the stage 1 conclusions came
from the single-broker simplification, plus one broker killed mid-input.
The order of structures is shuffled per repeat with a fixed seed so no structure always runs first
or last; the executed order is written to plan.json.
"""
import argparse
import json
import random
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from evidence_paths import validate_evidence_path  # noqa: E402
from run import REPO, run_case, PROCESSES, HANDLES, stop  # noqa: E402

ALL = ['sequential', 'async', 'parallel-consumer', 'retry-topic', 'inbox']
SWEEP_DURATION = 12


def shuffled(items, seed):
    items = list(items)
    random.Random(seed).shuffle(items)
    return items


def stage_plan(stage):
    plan = []
    if stage == 1:
        for repeat in range(1, 4):
            for mode in shuffled(ALL, 100 + repeat):
                plan.append(dict(mode=mode, scenario='api', repeat=repeat, workload='market', partitions=4))
            for mode in shuffled(['sequential', 'parallel-consumer', 'retry-topic', 'inbox'], 200 + repeat):
                plan.append(dict(mode=mode, scenario='hang', repeat=repeat, workload='market', partitions=4))
        for repeat in range(1, 4):
            cells = [(rate, mode) for rate in [80, 160, 320, 640] for mode in ALL]
            for rate, mode in shuffled(cells, 1000 + repeat):
                plan.append(dict(mode=mode, scenario='clean', repeat=repeat, workload='sweep', partitions=4,
                                 rate=rate, duration=SWEEP_DURATION))
    elif stage == 3:
        # Inbox v2 (one transaction per poll) on the stage 1 cluster.
        for repeat in range(1, 4):
            plan.append(dict(mode='inbox-batch', scenario='api', repeat=repeat, workload='market', partitions=4))
            plan.append(dict(mode='inbox-batch', scenario='hang', repeat=repeat, workload='market', partitions=4))
        for repeat in range(1, 4):
            for rate in shuffled([80, 160, 320, 640], 4000 + repeat):
                plan.append(dict(mode='inbox-batch', scenario='clean', repeat=repeat, workload='sweep', partitions=4,
                                 rate=rate, duration=SWEEP_DURATION))
    else:
        # asyncAcks was compared in stage 1; stage 2 checks external validity, not the full benchmark.
        for repeat in range(1, 4):
            cells = [(rate, mode) for rate in [160, 320, 640] for mode in ['sequential', 'parallel-consumer', 'retry-topic', 'inbox', 'inbox-batch']]
            for rate, mode in shuffled(cells, 2000 + repeat):
                plan.append(dict(mode=mode, scenario='clean', repeat=repeat, workload='sweep', partitions=4,
                                 rate=rate, duration=SWEEP_DURATION))
            for mode in shuffled(['retry-topic', 'inbox', 'inbox-batch'], 3000 + repeat):
                plan.append(dict(mode=mode, scenario='broker-failover', repeat=repeat, workload='sweep', partitions=4,
                                 rate=160, duration=20))
    return plan


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('--stage', type=int, choices=[1, 2, 3], required=True)
    p.add_argument('--evidence', required=True)
    a = p.parse_args()
    root = Path(a.evidence).resolve()
    validate_evidence_path(root)
    root.mkdir(parents=True, exist_ok=True)
    plan = stage_plan(a.stage)
    (root/'plan.json').write_text(json.dumps(plan, indent=2))
    failures = []
    try:
        for index, case in enumerate(plan, 1):
            print(f'[{index}/{len(plan)}] {json.dumps(case)}', flush=True)
            try:
                run_case(root, case['mode'], case['scenario'], case['repeat'], workload=case['workload'],
                         partitions=case['partitions'], rate=case.get('rate', 80), duration=case.get('duration', 12))
            except Exception as error:
                # One failed run must not cost the rest of the session; it stays in failures.json.
                failures.append(dict(case, error=repr(error), time=int(time.time() * 1000)))
                (root/'failures.json').write_text(json.dumps(failures, indent=2))
    finally:
        for process in PROCESSES: stop(process)
        for handle in HANDLES: handle.close()
    (root/'failures.json').write_text(json.dumps(failures, indent=2))
    for script in ['analyze.py', 'comparison_report.py', 'sweep_report.py']:
        subprocess.run([sys.executable, str(REPO/'experiments'/script), '--evidence', str(root)])
    print(json.dumps(dict(stage=a.stage, planned=len(plan), failed=len(failures))), flush=True)
    (root/'DONE').write_text('done\n')
