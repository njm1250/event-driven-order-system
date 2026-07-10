package com.ordersystem.order_service.dto;

import com.ordersystem.order_service.entity.Order;

import java.time.LocalDateTime;

public record OrderResponse(
        Long orderId,
        String productCode,
        int quantity,
        double price,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static OrderResponse from(Order order) {
        return new OrderResponse(
                order.getOrderId(),
                order.getProductCode(),
                order.getQuantity(),
                order.getPrice(),
                order.getOrderStatus().name(),
                order.getCreatedAt(),
                order.getUpdatedAt()
        );
    }
}
