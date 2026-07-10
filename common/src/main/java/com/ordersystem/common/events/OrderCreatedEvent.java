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
public class OrderCreatedEvent {

    private String eventId;
    private Instant occurredAt;
    private Long orderId;
    private String productCode;
    private int quantity;
    private double price;

    @Builder
    public OrderCreatedEvent(Long orderId, String productCode, int quantity, double price) {
        this.eventId = UUID.randomUUID().toString();
        this.occurredAt = Instant.now();
        this.orderId = orderId;
        this.productCode = productCode;
        this.quantity = quantity;
        this.price = price;
    }
}
