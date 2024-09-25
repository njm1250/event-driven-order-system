package com.ordersystem.common.events;

// 나중에 Builder 패턴 적용
public class OrderCreatedEvent {
    private String orderId;    // 주문 ID
    private String productId;  // 제품 ID
    private int quantity;      // 주문 수량
    private double price;      // 주문 가격
    private String orderStatus; // 주문 상태 (예: PENDING, CONFIRMED, CANCELLED)

    public OrderCreatedEvent() {
    }

    public OrderCreatedEvent(String orderId, String productId, int quantity, double price, String orderStatus) {
        this.orderId = orderId;
        this.productId = productId;
        this.quantity = quantity;
        this.price = price;
        this.orderStatus = orderStatus;
    }

    public String getOrderId() {
        return orderId;
    }

    public void setOrderId(String orderId) {
        this.orderId = orderId;
    }

    public String getProductId() {
        return productId;
    }

    public void setProductId(String productId) {
        this.productId = productId;
    }

    public int getQuantity() {
        return quantity;
    }

    public void setQuantity(int quantity) {
        this.quantity = quantity;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    public String getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(String orderStatus) {
        this.orderStatus = orderStatus;
    }

    @Override
    public String toString() {
        return "OrderCreatedEvent{" +
                "orderId='" + orderId + '\'' +
                ", productId='" + productId + '\'' +
                ", quantity=" + quantity +
                ", price=" + price +
                ", orderStatus='" + orderStatus + '\'' +
                '}';
    }
}
