#!/usr/bin/env bash
# Removes exactly the resources recorded in <evidence dir>/aws-resources.json, nothing else.
set -uo pipefail
out_dir="${1:?usage: teardown.sh <evidence dir>}"
ledger="$out_dir/aws-resources.json"
field() { python3 -c "import json,sys;print(json.load(open(sys.argv[1])).get(sys.argv[2],''))" "$ledger" "$1"; }
region=$(field region); session=$(field session)
aws_() { aws --region "$region" "$@"; }
note() { echo "[$(date -u +%H:%M:%S)] $*" | tee -a "$out_dir/session.log"; }
instances=$(python3 -c "import json,sys;d=json.load(open(sys.argv[1]));print(' '.join(d.get('instances') or [d.get(k) for k in ('systemInstance','partnerInstance') if d.get(k)]))" "$ledger")
note "teardown start: $session"
if [[ -n "$instances" ]]; then
  aws_ ec2 terminate-instances --instance-ids $instances >/dev/null
  aws_ ec2 wait instance-terminated --instance-ids $instances
  note "terminated $instances"
fi
[[ -n "$(field terminateSchedule)" ]] && aws_ scheduler delete-schedule --name "$(field terminateSchedule)" >/dev/null 2>&1
role=$(field terminateRole)
if [[ -n "$role" ]]; then
  aws iam delete-role-policy --role-name "$role" --policy-name terminate >/dev/null 2>&1
  aws iam delete-role --role-name "$role" >/dev/null 2>&1
fi
[[ -n "$(field securityGroup)" ]] && aws_ ec2 delete-security-group --group-id "$(field securityGroup)" >/dev/null
[[ -n "$(field keyPair)" ]] && aws_ ec2 delete-key-pair --key-name "$(field keyPair)" >/dev/null 2>&1
rm -f "$out_dir/$session.pem"
left=$(aws_ ec2 describe-volumes --filters "Name=tag:session,Values=$session" --query 'length(Volumes)' --output text)
note "teardown done, remaining tagged volumes: $left"
python3 - "$ledger" <<'PY'
import json,sys,datetime
data=json.load(open(sys.argv[1])); data['cleanupFinishedAt']=datetime.datetime.utcnow().strftime('%Y-%m-%dT%H:%M:%SZ')
json.dump(data,open(sys.argv[1],'w'),indent=2)
PY
