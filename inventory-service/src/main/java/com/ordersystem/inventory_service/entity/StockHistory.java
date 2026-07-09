package com.ordersystem.inventory_service.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 재고 차감 처리 이력. 차감과 같은 트랜잭션으로 기록되어
 * 멱등 판정(eventId 중복 확인)의 기준이 된다. 운영 수준에서는 애플리케이션
 * 확인에 더해 event_id UNIQUE 제약을 최후 방어선으로 두는 것이 맞다.
 */
@Entity
@Getter
@NoArgsConstructor
@Table(name = "stock_history")
public class StockHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", length = 36, nullable = false)
    private String eventId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    private int delta;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public StockHistory(String eventId, Long orderId, int delta) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.delta = delta;
    }
}
