#!/usr/bin/env python3
"""Final additional checks: pool-independent evidence, broker death, hot key, retry budgets."""
import argparse
import subprocess
from pathlib import Path
from run import run_case, PROCESSES, HANDLES, stop, REPO
p=argparse.ArgumentParser();p.add_argument('--evidence',required=True);a=p.parse_args();root=Path(a.evidence).resolve()
if root==REPO or REPO in root.parents:raise SystemExit('Evidence must be external')
try:
    for mode in ['sequential','async','inbox']:
        for scenario in ['hotkey','broker-kill','retry']:
            result=run_case(root,mode,scenario,2)
            subprocess.run(['python3',str(REPO/'experiments/verify_budgets.py'),str(result)],check=True)
finally:
    for p in PROCESSES:stop(p)
    for f in HANDLES:f.close()
