#!/usr/bin/env bash
# One bounded AWS session for the representative comparison.
#   system host (SYSTEM_TYPE, default m7i-flex.large): Kafka, MySQL, partner service, controller, collector
#   partner host (PARTNER_TYPE, default c7i-flex.large): mock seller API, reached over the VPC
# The defaults are AWS Free plan eligible; a paid account can pass m6i.xlarge / m6i.large instead.
# Three independent stops: OS shutdown timer (terminate on shutdown), an EventBridge Scheduler
# terminate at the deadline, and the cleanup trap below. Only resources created here are removed.
set -euo pipefail

region=ap-northeast-2
max_hours=${MAX_HOURS:-5}
system_type=${SYSTEM_TYPE:-m7i-flex.large}
partner_type=${PARTNER_TYPE:-c7i-flex.large}
repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
out_dir="${1:?usage: session.sh <evidence dir>}"
mkdir -p "$out_dir"
session="partner-isolation-$(date -u +%Y%m%d%H%M%S)"
ledger="$out_dir/aws-resources.json"
key_file="$out_dir/$session.pem"
aws_() { aws --region "$region" "$@"; }
note() { echo "[$(date -u +%H:%M:%S)] $*" | tee -a "$out_dir/session.log"; }
record() { python3 - "$ledger" "$1" "$2" <<'PY'
import json,sys,os
path,key,value=sys.argv[1:]
data=json.load(open(path)) if os.path.exists(path) else {}
data[key]=value
json.dump(data,open(path,'w'),indent=2)
PY
}

instances=(); sg=""; role=""; schedule=""
cleanup() {
  set +e
  note "cleanup start"
  if ((${#instances[@]})); then
    aws_ ec2 terminate-instances --instance-ids "${instances[@]}" >/dev/null
    aws_ ec2 wait instance-terminated --instance-ids "${instances[@]}"
    note "terminated ${instances[*]}"
  fi
  [[ -n "$schedule" ]] && aws_ scheduler delete-schedule --name "$schedule" >/dev/null 2>&1
  if [[ -n "$role" ]]; then
    aws iam delete-role-policy --role-name "$role" --policy-name terminate >/dev/null 2>&1
    aws iam delete-role --role-name "$role" >/dev/null 2>&1
  fi
  [[ -n "$sg" ]] && aws_ ec2 delete-security-group --group-id "$sg" >/dev/null
  aws_ ec2 delete-key-pair --key-name "$session" >/dev/null 2>&1
  rm -f "$key_file"
  # Root volumes use DeleteOnTermination; confirm nothing tagged with this session is left.
  left=$(aws_ ec2 describe-volumes --filters "Name=tag:session,Values=$session" --query 'length(Volumes)' --output text)
  note "cleanup done, remaining tagged volumes: $left"
  record cleanupFinishedAt "$(date -u +%FT%TZ)"
}
trap cleanup EXIT

record session "$session"; record region "$region"; record systemType "$system_type"; record partnerType "$partner_type"; record startedAt "$(date -u +%FT%TZ)"
account=$(aws sts get-caller-identity --query Account --output text)
my_ip=$(curl -fsS https://checkip.amazonaws.com)/32
vpc=$(aws_ ec2 describe-vpcs --filters Name=is-default,Values=true --query 'Vpcs[0].VpcId' --output text)
subnet=$(aws_ ec2 describe-subnets --filters Name=vpc-id,Values="$vpc" Name=availability-zone,Values=${region}a \
  --query 'Subnets[0].SubnetId' --output text)
ami=$(aws_ ssm get-parameter --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 \
  --query Parameter.Value --output text)
record ami "$ami"; record subnet "$subnet"

aws_ ec2 create-key-pair --key-name "$session" --query KeyMaterial --output text > "$key_file"
chmod 600 "$key_file"; record keyPair "$session"
sg=$(aws_ ec2 create-security-group --group-name "$session" --description "$session" --vpc-id "$vpc" --query GroupId --output text)
record securityGroup "$sg"
aws_ ec2 authorize-security-group-ingress --group-id "$sg" --protocol tcp --port 22 --cidr "$my_ip" >/dev/null
aws_ ec2 authorize-security-group-ingress --group-id "$sg" --protocol tcp --port 8099 --source-group "$sg" >/dev/null

user_data=$(cat <<EOF
#!/bin/bash
shutdown -h +$((max_hours * 60))
dnf install -y docker git python3 java-17-amazon-corretto-headless chrony >/dev/null
systemctl enable --now docker chronyd
mkdir -p /usr/local/lib/docker/cli-plugins
curl -fsSL https://github.com/docker/compose/releases/download/v2.29.7/docker-compose-linux-x86_64 \
  -o /usr/local/lib/docker/cli-plugins/docker-compose && chmod +x /usr/local/lib/docker/cli-plugins/docker-compose
usermod -aG docker ec2-user
touch /var/tmp/ready
EOF
)
launch() {
  aws_ ec2 run-instances --image-id "$ami" --instance-type "$1" --subnet-id "$subnet" --security-group-ids "$sg" \
    --key-name "$session" --instance-initiated-shutdown-behavior terminate --user-data "$user_data" \
    --block-device-mappings 'DeviceName=/dev/xvda,Ebs={VolumeSize=30,VolumeType=gp3,DeleteOnTermination=true}' \
    --tag-specifications "ResourceType=instance,Tags=[{Key=session,Value=$session},{Key=Name,Value=$session-$2}]" \
      "ResourceType=volume,Tags=[{Key=session,Value=$session}]" \
    --query 'Instances[0].InstanceId' --output text
}
system=$(launch "$system_type" system); instances+=("$system"); record systemInstance "$system"
partner=$(launch "$partner_type" partner-api); instances+=("$partner"); record partnerInstance "$partner"
note "launched $system $partner"

# AWS-side deadline that works even if neither OS nor this laptop is alive.
role="$session-terminate"
aws iam create-role --role-name "$role" --assume-role-policy-document \
  '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"scheduler.amazonaws.com"},"Action":"sts:AssumeRole"}]}' >/dev/null
record terminateRole "$role"
aws iam put-role-policy --role-name "$role" --policy-name terminate --policy-document \
  "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"ec2:TerminateInstances\",\"Resource\":[\"arn:aws:ec2:$region:$account:instance/$system\",\"arn:aws:ec2:$region:$account:instance/$partner\"]}]}"
sleep 10  # IAM propagation before the scheduler validates the role
deadline=$(date -u -v+${max_hours}H +%Y-%m-%dT%H:%M:%S 2>/dev/null || date -u -d "+$max_hours hours" +%Y-%m-%dT%H:%M:%S)
schedule="$session"
aws_ scheduler create-schedule --name "$schedule" --schedule-expression "at($deadline)" --schedule-expression-timezone UTC \
  --flexible-time-window Mode=OFF --action-after-completion DELETE \
  --target "{\"Arn\":\"arn:aws:scheduler:::aws-sdk:ec2:terminateInstances\",\"RoleArn\":\"arn:aws:iam::$account:role/$role\",\"Input\":\"{\\\"InstanceIds\\\":[\\\"$system\\\",\\\"$partner\\\"]}\"}" >/dev/null
aws_ scheduler get-schedule --name "$schedule" --query ScheduleExpression --output text | tee -a "$out_dir/session.log"
record terminateDeadlineUtc "$deadline"

aws_ ec2 wait instance-running --instance-ids "$system" "$partner"
read -r system_ip system_private <<<"$(aws_ ec2 describe-instances --instance-ids "$system" --query 'Reservations[0].Instances[0].[PublicIpAddress,PrivateIpAddress]' --output text)"
read -r partner_ip partner_private <<<"$(aws_ ec2 describe-instances --instance-ids "$partner" --query 'Reservations[0].Instances[0].[PublicIpAddress,PrivateIpAddress]' --output text)"
ssh_() { local host=$1; shift; ssh -i "$key_file" -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile="$out_dir/known_hosts" -o ConnectTimeout=10 "ec2-user@$host" "$@"; }
scp_() { scp -i "$key_file" -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile="$out_dir/known_hosts" "$@"; }
for host in "$system_ip" "$partner_ip"; do
  for _ in $(seq 60); do ssh_ "$host" test -f /var/tmp/ready 2>/dev/null && break; sleep 10; done
  ssh_ "$host" test -f /var/tmp/ready
done
note "hosts ready"
for host in "$system_ip" "$partner_ip"; do
  ssh_ "$host" 'uname -m; nproc; free -m | head -2; chronyc tracking | grep -E "System time|Reference ID"' >> "$out_dir/hosts.txt"
done

# Partner API host: one long-lived mock, a fresh ledger per run through /reset.
scp_ "$repo_dir/experiments/mock-partner-api/server.py" "ec2-user@$partner_ip:server.py"
ssh_ "$partner_ip" 'mkdir -p ledgers && nohup python3 server.py --host 0.0.0.0 --database ledgers/initial.sqlite > mock.log 2>&1 < /dev/null &'

# System host: the exact commit and the locally built jars.
git -C "$repo_dir" bundle create "$out_dir/repo.bundle" HEAD >/dev/null 2>&1
scp_ "$out_dir/repo.bundle" "ec2-user@$system_ip:repo.bundle"
ssh_ "$system_ip" 'git clone -q repo.bundle repo && mkdir -p repo/partner-integration-service/build/libs repo/order-service/build/libs repo/inventory-service/build/libs'
for module in partner-integration-service order-service inventory-service; do
  scp_ "$repo_dir/$module/build/libs/app.jar" "ec2-user@$system_ip:repo/$module/build/libs/app.jar"
done
ssh_ "$system_ip" "cd repo && docker compose -p partner-isolation -f docker-compose.experiment.yml up -d --wait" >> "$out_dir/session.log" 2>&1
note "stack up, partner API at $partner_private"

ssh_ "$system_ip" "cd repo && PARTNER_API_URL=http://$partner_private:8099 python3 experiments/aws_comparison.py --evidence experiment-evidence/aws" \
  2>&1 | tee -a "$out_dir/remote-run.log" || note "remote run failed; collecting what exists"
ssh_ "$system_ip" "cd repo/experiment-evidence && tar czf ../../aws-evidence.tgz aws" || true
scp_ "ec2-user@$system_ip:aws-evidence.tgz" "$out_dir/" && tar xzf "$out_dir/aws-evidence.tgz" -C "$out_dir"
scp_ "ec2-user@$partner_ip:mock.log" "$out_dir/partner-mock.log" || true
shasum -a 256 "$out_dir/aws-evidence.tgz" > "$out_dir/aws-evidence.sha256" || true
note "results copied"
