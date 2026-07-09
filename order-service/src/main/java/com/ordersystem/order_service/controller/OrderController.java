package com.ordersystem.order_service.controller;

import com.ordersystem.order_service.entity.Order;
import com.ordersystem.order_service.entity.OrderStatus;
import com.ordersystem.order_service.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<String> createOrder() {
        Order order = Order.builder()
                .productCode("P001")
                .quantity(1)
                .price(1000)
                .orderStatus(OrderStatus.PENDING)
                .build();

        orderService.processCreateOrder(order);
        return ResponseEntity.status(HttpStatus.CREATED).body("Order created successfully");
    }
}
