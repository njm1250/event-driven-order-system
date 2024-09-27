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

    @Builder
    public Order(String productCode, int quantity, double price) {
        this.productCode = productCode;
        this.quantity = quantity;
        this.price = price;
    }
}