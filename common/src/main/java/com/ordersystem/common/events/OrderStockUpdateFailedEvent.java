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
public class OrderStockUpdateFailedEvent {

    private String eventId;
    private Instant occurredAt;
    private Long orderId;
    private String reason;

    @Builder
    public OrderStockUpdateFailedEvent(Long orderId, String reason) {
        this.eventId = UUID.randomUUID().toString();
        this.occurredAt = Instant.now();
        this.orderId = orderId;
        this.reason = reason;
    }
}
