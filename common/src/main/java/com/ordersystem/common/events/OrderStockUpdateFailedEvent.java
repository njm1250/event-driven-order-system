package com.ordersystem.common.events;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class OrderStockUpdateFailedEvent {
    private Long orderId;
    private String reason;

    @Builder
    public OrderStockUpdateFailedEvent(Long orderId, String reason) {
        this.orderId = orderId;
        this.reason = reason;
    }
}
