package com.ordersystem.order_service.controller;

import com.ordersystem.order_service.entity.Order;
import com.ordersystem.order_service.entity.OrderStatus;
import com.ordersystem.order_service.service.OrderService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ordersystem.common.events.OrderCreatedEvent;

@RestController
@RequestMapping("/orders")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @PostMapping
    public ResponseEntity<String> createOrder() {
        try {
            Order order = Order.builder()
                    .productCode("P001")
                    .quantity(1)
                    .price(1000)
                    .orderStatus(OrderStatus.PENDING)
                    .build();

            orderService.createOrder(order);
            return ResponseEntity.status(HttpStatus.CREATED).body("Order created successfully");

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to create order: " + e.getMessage());
        }
    }
}
