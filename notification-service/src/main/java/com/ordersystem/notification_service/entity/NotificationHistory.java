package com.ordersystem.notification_service.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 알림 발송 이력. 발송과 같은 트랜잭션으로 기록되어 멱등 판정의 기준이 된다.
 * 운영 수준에서는 event_id UNIQUE 제약을 최후 방어선으로 두는 것이 맞다.
 */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "notification_history")
public class NotificationHistory {

    public enum Type { ORDER_CONFIRMED, ORDER_CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", length = 36, nullable = false)
    private String eventId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @CreationTimestamp
    @Column(name = "sent_at", updatable = false)
    private LocalDateTime sentAt;

    @Builder
    public NotificationHistory(String eventId, Long orderId, Type type) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.type = type;
    }
}
