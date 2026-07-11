# Event-Driven Order Management System

Spring Boot와 Kafka로 주문·재고·알림 서비스를 분리한 이벤트 기반 주문 처리 프로젝트입니다.

## Tech Stack

- Java 17
- Spring Boot 3.2
- Spring Kafka
- Spring Data JPA
- Kafka 3.9
- MySQL 8.4
- Docker Compose

## Services

### Order Service

- 주문 생성과 상태 조회
- 주문과 Outbox 이벤트를 같은 트랜잭션으로 저장
- 재고 처리 결과에 따라 주문 상태 갱신

### Inventory Service

- 주문 생성 이벤트 소비
- 재고 차감과 이벤트 처리 이력을 같은 트랜잭션으로 저장
- 재고 처리 성공·실패 이벤트 발행

### Notification Service

- 주문 확정·취소 알림 처리
- 실시간 알림과 대량 발송 요청을 별도 토픽과 Consumer Group으로 처리
- 실시간·대량 발송 요청이 하나의 외부 발송 처리 한도를 공유하도록 제한
- 처리할 수 없는 메시지를 DLT로 격리

## Event Flow

```text
Order Created
    │
    ▼
Order Service ── Kafka ──▶ Inventory Service
    ▲                              │
    │                              ▼
    └──── Stock Updated / Failed Event
                   │
                   └──────────────▶ Notification Service
```

알림 발송 요청은 용도에 따라 별도 경로로 처리합니다.

```text
Realtime Notification ──▶ Realtime Topic ──▶ Realtime Consumers ─┐
                                                                  ├─▶ Sender
Bulk Notification ──────▶ Bulk Topic ──────▶ Bulk Consumers ─────┘
```

## Reliability

- Transactional Outbox로 DB 저장과 이벤트 발행 사이의 유실 방지
- 이벤트 처리 이력으로 at-least-once 재전달에 대한 멱등성 확보
- 재시도 상한과 DLT로 처리 불가능한 메시지 격리

## Run

```bash
docker compose up -d --build
```

주문 생성 API는 `http://localhost:8081/orders`에서 사용할 수 있습니다.

## Test

```bash
./gradlew test
```
