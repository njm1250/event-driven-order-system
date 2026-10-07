#!/usr/bin/env bash
# Component-per-host AWS cluster for the comparison and throughput experiments.
#   cluster.sh up <dir> <kafka brokers: 1|3>   create hosts, start Kafka/MySQL/mock, ship code
#   cluster.sh run <dir> <stage: 1|2>          start the suite on the controller (detached)
#   cluster.sh status <dir>                    progress of the suite
#   cluster.sh fetch <dir>                     copy results back and checksum them
#   cluster.sh down <dir>                      remove exactly the recorded resources
# Every host is m7i.large (2 vCPU, non-Flex) so CPU supply is not a variable. Each host shuts itself
# down after MAX_HOURS (terminate on shutdown) and an EventBridge schedule terminates them as well.
set -Eeuo pipefail

region=ap-northeast-2
zone=${region}a
instance_type=${INSTANCE_TYPE:-m7i.large}
max_hours=${MAX_HOURS:-5}
repo_dir="$(cd "$(dirname "$0")/../.." && pwd)"
command="${1:?usage: cluster.sh up|run|status|fetch|down <dir> [arg]}"
out_dir="$(mkdir -p "${2:?evidence dir}" && cd "$2" && pwd)"
ledger="$out_dir/aws-resources.json"
aws_() { aws --region "$region" "$@"; }
note() { echo "[$(date -u +%H:%M:%S)] $*" | tee -a "$out_dir/session.log"; }
field() { python3 -c "import json,sys;print(json.load(open(sys.argv[1])).get(sys.argv[2],''))" "$ledger" "$1"; }
record() { python3 - "$ledger" "$1" "$2" <<'PY'
import json,sys,os
path,key,value=sys.argv[1:]
data=json.load(open(path)) if os.path.exists(path) else {}
try: data[key]=json.loads(value)
except ValueError: data[key]=value
json.dump(data,open(path,'w'),indent=2)
PY
}
host_ip() { python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['hosts'][sys.argv[2]][sys.argv[3]])" "$ledger" "$1" "$2"; }
key_file() { echo "$out_dir/$(field session).pem"; }
ssh_() { local host=$1; shift; ssh -i "$(key_file)" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR -o ConnectTimeout=10 "ec2-user@$host" "$@"; }
scp_() { scp -i "$(key_file)" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR "$@"; }

up() {
  local brokers=${1:?kafka broker count}
  local session="partner-cluster-$(date -u +%Y%m%d%H%M%S)"
  record session "$session"; record region "$region"; record instanceType "$instance_type"; record brokers "$brokers"
  record startedAt "$(date -u +%FT%TZ)"
  # Any failure before the hosts are recorded as ready removes what was created.
  trap 'note "up failed; tearing down"; bash "$repo_dir/experiments/aws/teardown.sh" "$out_dir"' ERR

  local offered
  offered=$(aws_ ec2 describe-instance-type-offerings --location-type availability-zone \
    --filters Name=location,Values=$zone Name=instance-type,Values="$instance_type" --query 'length(InstanceTypeOfferings)' --output text)
  [[ "$offered" == 1 ]] || { note "$instance_type not offered in $zone"; return 1; }
  local account my_ip vpc subnet ami sg
  account=$(aws sts get-caller-identity --query Account --output text)
  my_ip=$(curl -fsS https://checkip.amazonaws.com)/32
  vpc=$(aws_ ec2 describe-vpcs --filters Name=is-default,Values=true --query 'Vpcs[0].VpcId' --output text)
  subnet=$(aws_ ec2 describe-subnets --filters Name=vpc-id,Values="$vpc" Name=availability-zone,Values=$zone --query 'Subnets[0].SubnetId' --output text)
  ami=$(aws_ ssm get-parameter --name /aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-x86_64 --query Parameter.Value --output text)
  record ami "$ami"; record subnet "$subnet"

  aws_ ec2 create-key-pair --key-name "$session" --query KeyMaterial --output text > "$out_dir/$session.pem"
  chmod 600 "$out_dir/$session.pem"; record keyPair "$session"
  sg=$(aws_ ec2 create-security-group --group-name "$session" --description "$session" --vpc-id "$vpc" --query GroupId --output text)
  record securityGroup "$sg"
  aws_ ec2 authorize-security-group-ingress --group-id "$sg" --protocol tcp --port 22 --cidr "$my_ip" >/dev/null
  aws_ ec2 authorize-security-group-ingress --group-id "$sg" --protocol -1 --source-group "$sg" >/dev/null

  local user_data
  user_data=$(cat <<EOF
#!/bin/bash
shutdown -h +$((max_hours * 60))
dnf install -y docker git python3 java-17-amazon-corretto-headless mariadb105 >/dev/null
systemctl enable --now docker
usermod -aG docker ec2-user
touch /var/tmp/ready
EOF
)
  local roles=(mysql partner mock controller)
  for ((i = 1; i <= brokers; i++)); do roles+=("kafka-$i"); done
  local ids=()
  for role in "${roles[@]}"; do
    local id
    id=$(aws_ ec2 run-instances --image-id "$ami" --instance-type "$instance_type" --subnet-id "$subnet" --security-group-ids "$sg" \
      --key-name "$session" --instance-initiated-shutdown-behavior terminate --user-data "$user_data" \
      --block-device-mappings 'DeviceName=/dev/xvda,Ebs={VolumeSize=30,VolumeType=gp3,DeleteOnTermination=true}' \
      --tag-specifications "ResourceType=instance,Tags=[{Key=session,Value=$session},{Key=Name,Value=$session-$role},{Key=role,Value=$role}]" \
        "ResourceType=volume,Tags=[{Key=session,Value=$session}]" \
      --query 'Instances[0].InstanceId' --output text)
    ids+=("$id")
    record instances "$(python3 -c "import json,sys;print(json.dumps(sys.argv[1:]))" "${ids[@]}")"
  done
  note "launched ${#ids[@]} x $instance_type: ${roles[*]}"

  local role="$session-terminate"
  aws iam create-role --role-name "$role" --assume-role-policy-document \
    '{"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"scheduler.amazonaws.com"},"Action":"sts:AssumeRole"}]}' >/dev/null
  record terminateRole "$role"
  local arns
  arns=$(python3 -c "import json,sys;print(json.dumps([f'arn:aws:ec2:{sys.argv[1]}:{sys.argv[2]}:instance/{i}' for i in sys.argv[3:]]))" "$region" "$account" "${ids[@]}")
  aws iam put-role-policy --role-name "$role" --policy-name terminate --policy-document \
    "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"ec2:TerminateInstances\",\"Resource\":$arns}]}"
  sleep 10
  local deadline input
  deadline=$(date -u -v+${max_hours}H +%Y-%m-%dT%H:%M:%S 2>/dev/null || date -u -d "+$max_hours hours" +%Y-%m-%dT%H:%M:%S)
  input=$(python3 -c "import json,sys;print(json.dumps(json.dumps({'InstanceIds':sys.argv[1:]})))" "${ids[@]}")
  aws_ scheduler create-schedule --name "$session" --schedule-expression "at($deadline)" --schedule-expression-timezone UTC \
    --flexible-time-window Mode=OFF --action-after-completion DELETE \
    --target "{\"Arn\":\"arn:aws:scheduler:::aws-sdk:ec2:terminateInstances\",\"RoleArn\":\"arn:aws:iam::$account:role/$role\",\"Input\":$input}" >/dev/null
  record terminateSchedule "$session"; record terminateDeadlineUtc "$deadline"
  note "terminate deadline $deadline UTC"

  aws_ ec2 wait instance-running --instance-ids "${ids[@]}"
  local hosts="{}"
  for i in "${!ids[@]}"; do
    local addresses
    addresses=$(aws_ ec2 describe-instances --instance-ids "${ids[$i]}" --query 'Reservations[0].Instances[0].[PublicIpAddress,PrivateIpAddress]' --output text)
    hosts=$(python3 -c "import json,sys;h=json.loads(sys.argv[1]);p,q=sys.argv[3].split();h[sys.argv[2]]=dict(id=sys.argv[4],public=p,private=q);print(json.dumps(h))" "$hosts" "${roles[$i]}" "$addresses" "${ids[$i]}")
  done
  record hosts "$hosts"
  for role in "${roles[@]}"; do
    local ip; ip=$(host_ip "$role" public)
    for _ in $(seq 60); do ssh_ "$ip" test -f /var/tmp/ready 2>/dev/null && break; sleep 10; done
    ssh_ "$ip" test -f /var/tmp/ready
  done
  note "hosts ready"

  # Kafka: KRaft with combined broker/controller nodes. RF and min ISR follow the broker count.
  local cluster_id voters="" rf min_isr bootstrap=""
  cluster_id=$(python3 -c "import base64,uuid;print(base64.urlsafe_b64encode(uuid.uuid4().bytes).decode().rstrip('='))")
  if ((brokers >= 3)); then rf=3; min_isr=2; else rf=1; min_isr=1; fi
  for ((i = 1; i <= brokers; i++)); do
    voters+="${voters:+,}$i@$(host_ip kafka-$i private):9093"; bootstrap+="${bootstrap:+,}$(host_ip kafka-$i private):9092"
  done
  for ((i = 1; i <= brokers; i++)); do
    ssh_ "$(host_ip kafka-$i public)" "docker run -d --name kafka --network host --memory 1g \
      -e KAFKA_NODE_ID=$i -e KAFKA_PROCESS_ROLES=broker,controller -e KAFKA_CONTROLLER_QUORUM_VOTERS=$voters \
      -e KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093 \
      -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://$(host_ip kafka-$i private):9092 \
      -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=PLAINTEXT:PLAINTEXT,CONTROLLER:PLAINTEXT -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
      -e KAFKA_INTER_BROKER_LISTENER_NAME=PLAINTEXT -e CLUSTER_ID=$cluster_id \
      -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=$rf -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=$rf \
      -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=$min_isr -e KAFKA_DEFAULT_REPLICATION_FACTOR=$rf -e KAFKA_MIN_INSYNC_REPLICAS=$min_isr \
      -e KAFKA_AUTO_CREATE_TOPICS_ENABLE=false -e 'KAFKA_HEAP_OPTS=-Xms256m -Xmx512m' apache/kafka:3.9.1" >/dev/null
  done
  record kafka "{\"bootstrap\":\"$bootstrap\",\"replicationFactor\":$rf,\"minInsyncReplicas\":$min_isr}"

  # MySQL 8.4 on the host's gp3 volume (the local stack used tmpfs, so this adds real fsync cost).
  local mysql_ip; mysql_ip=$(host_ip mysql public)
  ssh_ "$mysql_ip" mkdir -p sql
  scp_ "$repo_dir/experiments/mysql-init.sql" "ec2-user@$mysql_ip:sql/init.sql"
  scp_ "$repo_dir/experiments/migrations/001-source-schema.sql" "ec2-user@$mysql_ip:sql/z001-source-schema.sql"
  scp_ "$repo_dir/partner-integration-service/src/main/resources/schema.sql" "ec2-user@$mysql_ip:sql/z002-partner-schema.sql"
  ssh_ "$mysql_ip" "docker run -d --name mysql --network host --memory 1g -e MYSQL_ROOT_PASSWORD=labpassword \
    -v /home/ec2-user/sql:/docker-entrypoint-initdb.d:ro mysql:8.4" >/dev/null

  local mock_ip; mock_ip=$(host_ip mock public)
  scp_ "$repo_dir/experiments/mock-partner-api/server.py" "ec2-user@$mock_ip:server.py"
  ssh_ "$mock_ip" mkdir -p ledgers
  ssh -f -i "$(key_file)" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR "ec2-user@$mock_ip" \
    'nohup python3 server.py --host 0.0.0.0 --database ledgers/initial.sqlite > mock.log 2>&1 < /dev/null'

  # Code: the exact commit and locally built jars on the partner host and the controller.
  git -C "$repo_dir" bundle create "$out_dir/repo.bundle" HEAD >/dev/null 2>&1
  record commit "$(git -C "$repo_dir" rev-parse HEAD)"
  for role in partner controller; do
    local ip; ip=$(host_ip $role public)
    scp_ "$out_dir/repo.bundle" "ec2-user@$ip:repo.bundle"
    ssh_ "$ip" 'git clone -q repo.bundle repo 2>/dev/null && mkdir -p repo/{partner-integration-service,order-service,inventory-service}/build/libs'
    for module in partner-integration-service order-service inventory-service; do
      scp_ "$repo_dir/$module/build/libs/app.jar" "ec2-user@$ip:repo/$module/build/libs/app.jar"
    done
  done
  local controller_ip; controller_ip=$(host_ip controller public)
  scp_ "$(key_file)" "ec2-user@$controller_ip:cluster.pem"
  ssh_ "$controller_ip" chmod 600 cluster.pem

  local cpu_hosts="mysql=$(host_ip mysql private),partner=$(host_ip partner private),mock=$(host_ip mock private)" kafka_hosts=""
  for ((i = 1; i <= brokers; i++)); do
    cpu_hosts+=",kafka-$i=$(host_ip kafka-$i private)"; kafka_hosts+="${kafka_hosts:+,}$(host_ip kafka-$i private)"
  done
  cat > "$out_dir/controller.env" <<EOF
export MYSQL_HOST=$(host_ip mysql private)
export PARTNER_HOST=$(host_ip partner private)
export PARTNER_SERVICE_URL=http://$(host_ip partner private):8090
export PARTNER_API_URL=http://$(host_ip mock private):8099
export KAFKA_BOOTSTRAP=$bootstrap
export KAFKA_HOSTS=$kafka_hosts
export KAFKA_REPLICATION_FACTOR=$rf
export SSH_KEY=/home/ec2-user/cluster.pem
export CPU_HOSTS=$cpu_hosts
EOF
  scp_ "$out_dir/controller.env" "ec2-user@$controller_ip:controller.env"

  # Readiness from the controller's point of view: MySQL schema, every broker, the partner API.
  ssh_ "$controller_ip" "source controller.env; for i in \$(seq 60); do mysql -h \$MYSQL_HOST -uroot -plabpassword -e 'SELECT 1 FROM partner_db.inbox LIMIT 1' >/dev/null 2>&1 && break; sleep 3; done; \
    mysql -h \$MYSQL_HOST -uroot -plabpassword -e 'SELECT COUNT(*) FROM partner_db.inbox' >/dev/null && \
    for h in \${KAFKA_HOSTS//,/ }; do for i in \$(seq 40); do timeout 2 bash -c \"</dev/tcp/\$h/9092\" 2>/dev/null && break; sleep 3; done; done && \
    curl -fs \$PARTNER_API_URL/health >/dev/null && echo controller-ready" | tee -a "$out_dir/session.log"
  trap - ERR
  note "cluster up: $brokers broker(s), RF $rf, min ISR $min_isr"
}

run() {
  local stage=${1:?stage}
  local ip; ip=$(host_ip controller public)
  # Detached on the controller: a laptop sleep or a dropped ssh does not stop the suite.
  ssh -f -i "$(key_file)" -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR "ec2-user@$ip" \
    "cd repo && source ../controller.env && nohup python3 experiments/aws/cluster_suite.py --stage $stage --evidence experiment-evidence/stage$stage > ../stage$stage.log 2>&1 < /dev/null"
  note "stage $stage started on the controller"
}

status() {
  local ip; ip=$(host_ip controller public)
  ssh_ "$ip" 'for f in stage*.log; do echo "== $f: $(grep -c "\"passed\": true" $f) passed, $(grep -c FAILED $f) failed"; tail -2 $f | cut -c1-160; done'
}

fetch() {
  local ip; ip=$(host_ip controller public)
  ssh_ "$ip" 'tar czf results.tgz stage*.log repo/experiment-evidence'
  scp_ "ec2-user@$ip:results.tgz" "$out_dir/results.tgz"
  tar xzf "$out_dir/results.tgz" -C "$out_dir"
  shasum -a 256 "$out_dir/results.tgz" | tee "$out_dir/results.sha256"
  for role in mock partner; do
    scp_ "ec2-user@$(host_ip $role public):*.log" "$out_dir/" 2>/dev/null || true
  done
  note "results fetched"
}

down() {
  bash "$repo_dir/experiments/aws/teardown.sh" "$out_dir"
}

case "$command" in
  up) up "${3:-1}" ;;
  run) run "${3:?stage}" ;;
  status) status ;;
  fetch) fetch ;;
  down) down ;;
  *) echo "unknown command $command"; exit 2 ;;
esac
