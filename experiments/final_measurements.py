#!/usr/bin/env python3
"""Matched full-stack memory and source polling comparison, after serial fault verification."""
import argparse
from pathlib import Path
from run import REPO,run_case,PROCESSES,HANDLES,stop
from end_to_end import run as end_to_end
p=argparse.ArgumentParser();p.add_argument('--evidence',required=True);a=p.parse_args();root=Path(a.evidence).resolve()
if root==REPO or REPO in root.parents:raise SystemExit('Evidence must be external')
try:
    for repetition in range(8,11):
        for mode in ['sequential','async','inbox']:run_case(root,mode,'api',repetition)
    for repetition in range(3):
        for poll in [1000,100]:end_to_end(root,'inbox',poll_ms=poll)
finally:
    for process in PROCESSES:stop(process)
    for handle in HANDLES:handle.close()
