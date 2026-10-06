# 판매처 주문 연동을 비교하는 이벤트 기반 주문 시스템

한 판매처의 외부 주문 접수 API가 느려질 때 같은 Kafka partition의 다른 판매처 주문까지 밀리는 조건을 재현하는 코드입니다. 기존 주문·재고·알림 서비스에 판매처 연동 서비스와 별도 모의 API를 추가했습니다. Java 17, Spring Boot 3.2.10, Kafka broker 3.9.1, MySQL 8.4를 사용합니다.

```bash
KAFKA_EVIDENCE_DIR="$HOME/Desktop/experiment-evidence/reproduction" bash experiments/reproduce.sh
```

Docker Compose, Python 3, Java가 필요합니다. 전용 `partner-isolation` 프로젝트로 실행하며 39092/13306/8081/8083/8090/8099 포트를 사용합니다. 종료 시 전용 Kafka·MySQL 컨테이너를 정리합니다.

실험 결과는 **Git 저장소 밖** `KAFKA_EVIDENCE_DIR`에 남습니다. 기본 경로는 `$HOME/Desktop/experiment-evidence/<실행 날짜>`입니다. 측정 결과는 코드 README에 복사하지 않습니다.

개별 실행은 `python3 experiments/run.py --help`, 주문 API부터의 실행은 `python3 experiments/end_to_end.py --help`를 참조하세요. 기존 MySQL 데이터를 보존하면서 새 스키마를 적용할 때는 `experiments/migrate.py --help`를 참조하세요. 기본 앱은 명시적 SQL 스키마를 검증하며 임의의 `ddl-auto=update`로 변경하지 않습니다.

기존 구현은 `baseline-2026-10-06-d28e898` 태그와 Git 이력에 보존되어 있습니다. 실제 구현 모듈은 common, order-service, inventory-service, notification-service, partner-integration-service입니다.
