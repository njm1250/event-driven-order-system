package com.ordersystem.order_service.entity;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
@Table(name = "orders")
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

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Builder
    public Order(String productCode, int quantity, double price, OrderStatus orderStatus) {
        this.productCode = productCode;
        this.quantity = quantity;
        this.price = price;
        this.orderStatus = orderStatus;
    }

    public boolean confirm() {
        return transitionTo(OrderStatus.CONFIRMED);
    }

    public boolean cancel() {
        return transitionTo(OrderStatus.CANCELLED);
    }

    // PENDING에서만 확정/취소로 전이한다. 중복 전달되거나 순서가 뒤집힌 이벤트가
    // 이미 확정된 결과를 덮어쓰지 못하게 하는 컨슈머 멱등성의 최소 단위.
    private boolean transitionTo(OrderStatus target) {
        if (this.orderStatus != OrderStatus.PENDING) {
            return false;
        }
        this.orderStatus = target;
        return true;
    }
}
