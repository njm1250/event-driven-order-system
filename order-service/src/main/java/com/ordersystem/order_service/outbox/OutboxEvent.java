package com.ordersystem.order_service.outbox;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Transactional Outbox 레코드. 비즈니스 변경(주문 저장)과 같은 트랜잭션으로
 * 기록되고, OutboxRelay가 브로커 발행을 따로 책임진다. DB 커밋과 브로커 발행
 * 사이에서 프로세스가 죽어도 이벤트가 유실되지 않는 것이 목적이다.
 */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "outbox_event")
public class OutboxEvent {

    public enum Status { PENDING, SENT }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", length = 36, nullable = false)
    private String eventId;

    @Column(name = "aggregate_id", nullable = false)
    private String aggregateId;

    @Column(nullable = false)
    private String topic;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Builder
    public OutboxEvent(String eventId, String aggregateId, String topic, String eventType, String payload) {
        this.eventId = eventId;
        this.aggregateId = aggregateId;
        this.topic = topic;
        this.eventType = eventType;
        this.payload = payload;
        this.status = Status.PENDING;
    }

    public void markSent() {
        this.status = Status.SENT;
        this.sentAt = LocalDateTime.now();
    }
}
