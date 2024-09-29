package com.ordersystem.common.events;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
public class OrderCreatedEvent {
    private Long orderId;
    private String productCode;
    private int quantity;
    private double price;
    private String orderStatus;

    @Builder
    public OrderCreatedEvent(Long orderId, String productCode, int quantity, double price, String orderStatus) {
        this.orderId = orderId;
        this.productCode = productCode;
        this.quantity = quantity;
        this.price = price;
        this.orderStatus = orderStatus;
    }
}
