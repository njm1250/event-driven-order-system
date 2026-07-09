package com.ordersystem.common.events;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
public class OrderStockUpdatedEvent {

    private String eventId;
    private Instant occurredAt;
    private Long orderId;

    @Builder
    public OrderStockUpdatedEvent(Long orderId) {
        this.eventId = UUID.randomUUID().toString();
        this.occurredAt = Instant.now();
        this.orderId = orderId;
    }
}
