package com.ordersystem.order_service.controller;

import com.ordersystem.order_service.entity.Order;
import com.ordersystem.order_service.entity.OrderStatus;
import com.ordersystem.order_service.service.OrderService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ordersystem.common.events.OrderCreatedEvent;

@RestController
@RequestMapping("/order")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @GetMapping("/test")
    public ResponseEntity<String> test() {
        Order order = Order.builder()
                .productCode("P12345")
                .quantity(1)
                .price(1000)
                .orderStatus(OrderStatus.PENDING)
                .build();
        orderService.createOrder(order);
        return ResponseEntity.ok("test");
    }
}
