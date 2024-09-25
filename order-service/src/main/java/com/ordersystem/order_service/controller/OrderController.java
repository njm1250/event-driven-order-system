package com.ordersystem.order_service.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ordersystem.common.events.OrderCreatedEvent;

@RestController
@RequestMapping("/orders")
public class OrderController {

    @GetMapping("/test")
    public ResponseEntity<String> test() {
        OrderCreatedEvent event = new OrderCreatedEvent("order123", "product456", 2, 50.0, "PENDING");
        return ResponseEntity.ok("test");
    }
}
