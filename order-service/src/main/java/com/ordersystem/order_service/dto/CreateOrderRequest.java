package com.ordersystem.order_service.dto;

public record CreateOrderRequest(String productCode, int quantity, double price, String sellerId, String runId) {

    public boolean isInvalid() {
        return productCode == null || productCode.isBlank() || quantity <= 0 || price < 0 || !Double.isFinite(price) || (runId != null && (runId.isBlank() || runId.length() > 64)) || (sellerId != null && !sellerId.matches("[a-zA-Z0-9_-]{1,32}"));
    }
}
