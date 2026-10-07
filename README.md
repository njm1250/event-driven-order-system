# 이벤트 기반 주문 · 판매처 연동 시스템

주문 생성·변경·취소를 재고 확인을 거쳐 각 판매처의 주문 접수 API로 전달하는 시스템입니다. 서비스들은 서로를 직접 호출하지 않고 Kafka 이벤트로 연결됩니다.

Java 17, Spring Boot 3.2, Spring Kafka, Kafka 3.9, MySQL 8.4

## 서비스

| 서비스 | 역할 |
| --- | --- |
| `order-service` | 주문 생성·변경·취소 API. 주문 상태와 변경 순번을 관리하고, DB 변경과 이벤트를 같은 트랜잭션의 Outbox로 남겨 발행합니다. |
| `inventory-service` | 주문 이벤트를 받아 재고를 차감하고, 처리 결과를 Outbox로 발행합니다. 처리 이력으로 같은 이벤트의 중복 차감을 막습니다. |
| `partner-integration-service` | 확정된 주문과 변경·취소를 판매처 주문 접수 API로 전달합니다. 느린 판매처가 다른 판매처의 전달을 막지 않도록 처리합니다. |
| `notification-service` | 재고 처리 결과로 주문 결과 알림을 보내고, 알림 요청은 실시간과 대량을 다른 lane으로 나눠 처리합니다. |
| `common` | 서비스 간 이벤트 계약과 topic 이름 |

## 판매처 연동 서비스 구성

| 컴포넌트 | 역할 |
| --- | --- |
| `kafka` | 판매처 이벤트 수신. 받은 메시지를 inbox에 저장한 뒤 offset을 commit합니다. |
| `inbox` | 수신한 이벤트를 보관하고, 각 주문의 다음 순번 작업을 꺼내 줍니다. |
| `dispatch` | 워커 배정. 판매처별 동시 실행과 재시도 횟수에 상한을 둡니다. |
| `processing` | 순서 확인, 판매처 API 호출(멱등 키 포함), 전달 결과 기록 |

## 이벤트 흐름

```
order-service ──▶ inventory-order-created ──▶ inventory-service
inventory-service ──▶ order-stock-update / order-stock-update-failed ──▶ order-service
order-service ──▶ partner-order-requests ──▶ partner-integration-service ──▶ 판매처 API
inventory-service ──▶ order-stock-update / order-stock-update-failed ──▶ notification-service
알림 요청 ──▶ notification-requests-realtime / notification-requests-bulk ──▶ notification-service
```
