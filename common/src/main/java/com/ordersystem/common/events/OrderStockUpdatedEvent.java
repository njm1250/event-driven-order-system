package com.ordersystem.common.events;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class OrderStockUpdatedEvent {
    private Long orderId;

    @Builder
    public OrderStockUpdatedEvent(Long orderId) {
        this.orderId = orderId;
    }
}
