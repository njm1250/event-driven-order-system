#!/usr/bin/env python3
"""Complete the local comparison after preserved development runs; evidence stays external."""
import argparse
import subprocess
import sys
from pathlib import Path
from run import REPO, run_case, PROCESSES, HANDLES, stop
from end_to_end import run as end_to_end
p=argparse.ArgumentParser();p.add_argument('--evidence',required=True);a=p.parse_args();root=Path(a.evidence).resolve()
if root==REPO or REPO in root.parents:raise SystemExit('Evidence must be external')
try:
    for repetition in range(5,8):
        for mode in ['sequential','async','inbox']:run_case(root,mode,'api',repetition)
    for mode in ['sequential','async','inbox']:
        for scenario in ['db','response-loss','redelivery']:run_case(root,mode,scenario,2)
    for mode in ['sequential','async']:run_case(root,mode,'ack-release',2)
    run_case(root,'inbox','backlog',2)
    for repetition in range(1,4):
        for mode in ['sequential','async','inbox']:
            run_case(root,mode,'clean',repetition)
            run_case(root,mode,'clean',repetition,False)
    for mode in ['sequential','async','inbox']:
        end_to_end(root,mode)
        end_to_end(root,mode,seller_fault=True)
    for boundary in ['broker_ack','inventory_broker_ack']:
        for repetition in range(2):end_to_end(root,'inbox',boundary)
    end_to_end(root,'inbox',concurrent_duplicate=True)
    subprocess.run([sys.executable,str(REPO/'experiments/extra_cases.py'),'--evidence',str(root)],check=True)
    subprocess.run([sys.executable,str(REPO/'experiments/admission.py'),'--evidence',str(root)],check=True)
finally:
    for process in PROCESSES:stop(process)
    for handle in HANDLES:handle.close()
