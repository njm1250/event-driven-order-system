# Event-driven Order & Notification System

Java 17·Spring Boot·Kafka·MySQL로 주문 상태 전이와 알림 디스패치를 분리한
이벤트 기반 프로토타입이다.

## 모듈

- `order-service`: 주문 저장, Transactional Outbox 발행, 재고 처리 결과 반영
- `inventory-service`: 재고 차감과 처리 이력을 한 트랜잭션으로 반영
- `notification-service`: 주문 결과 알림과 실시간·대량 발송 요청 처리
- `common`: 서비스 간 이벤트 계약과 토픽 이름

## 실시간·대량 알림 lane 분리

대량 캠페인과 주문·결제 같은 실시간 알림이 하나의 FIFO 토픽을 공유하면,
처리 한도를 넘는 대량 요청이 쌓일 때 실시간 알림도 backlog 뒤에서 지연된다.
메시지에 priority 필드만 추가해도 이미 Kafka 로그에 기록된 순서를 바꿀 수 없다.

```text
notification-requests-realtime ── realtime consumers ─┐
                                                      ├─ shared dispatch limit ─ sender
notification-requests-bulk ────── bulk consumers ─────┘
```

- 실시간과 대량 발송 토픽·consumer group을 분리해 Kafka backlog를 격리한다.
- 두 lane은 하나의 디스패치 처리 한도를 공유한다.
- 실시간 요청이 기다리면 새 대량 요청보다 먼저 처리 슬롯을 얻는다.
- `userId`를 Kafka key로 사용해 같은 사용자의 순서는 유지하면서 사용자 간 처리를 병렬화한다.
- 처리할 수 없는 메시지는 lane별 DLT로 격리한다.

## 측정 결과와 비용

실제 푸시 대신 처리 한도가 고정된 Fake Sender를 사용했다. 2 vCPU·1GB의
notification service에 대량 요청 100만 건을 초당 2만 건, 실시간 알림을 초당
100건씩 함께 유입하고 조건별로 세 번 반복했다.

| 중앙값 | 단일 FIFO 토픽 | lane 분리 |
| --- | ---: | ---: |
| 실시간 p99 | 58,239ms | 71ms |
| 전체 처리량 | 9,259건/초 | 8,311건/초 |
| 대량 발송 p99 | 58,271ms | 70,591ms |

실시간 p99를 약 820배 줄이는 대신 전체 처리량은 89.8%를 유지했고, 대량 발송
p99는 12.3초 늘어났다. 실제 FCM·APNs 처리량이나 모바일 기기 도달 시간으로
해석하지 않는다.

## 검증

```bash
./gradlew test
```
