package com.ordersystem.order_service.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Table(name="orders")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "product_cd")
    private String productCode;

    private int quantity;

    private double price;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status")
    private OrderStatus orderStatus;

    @Builder
    public Order(String productCode, int quantity, double price, OrderStatus orderStatus) {
        this.productCode = productCode;
        this.quantity = quantity;
        this.price = price;
        this.orderStatus = orderStatus;
    }
}