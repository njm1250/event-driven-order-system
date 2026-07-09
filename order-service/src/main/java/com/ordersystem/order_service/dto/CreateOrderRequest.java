package com.ordersystem.order_service.dto;

public record CreateOrderRequest(String productCode, int quantity, double price) {

    public boolean isInvalid() {
        return productCode == null || productCode.isBlank() || quantity <= 0 || price < 0;
    }
}
