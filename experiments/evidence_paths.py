"""Keep experiment output out of tracked source, including ignored local folders."""
import subprocess
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
DEFAULT_EVIDENCE = REPO / 'experiment-evidence' / 'manual'

def validate_evidence_path(path):
    path = Path(path).expanduser().resolve()
    if path == REPO:
        raise SystemExit('Evidence cannot overwrite the repository root')
    if REPO in path.parents:
        relative = path.relative_to(REPO).as_posix()
        ignored = subprocess.run(
            ['git', '-C', str(REPO), 'check-ignore', '-q', '--', relative + '/'],
            check=False,
        )
        tracked = subprocess.run(
            ['git', '-C', str(REPO), 'ls-files', '-z', '--', relative],
            check=True, capture_output=True,
        )
        if ignored.returncode != 0 or tracked.stdout:
            raise SystemExit('Evidence inside the repository must be ignored and contain no tracked files')
    return path

if __name__ == '__main__':
    import sys
    validate_evidence_path(sys.argv[1])
