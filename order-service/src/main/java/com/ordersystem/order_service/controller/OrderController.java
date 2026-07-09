package com.ordersystem.order_service.controller;

import com.ordersystem.order_service.dto.CreateOrderRequest;
import com.ordersystem.order_service.dto.OrderResponse;
import com.ordersystem.order_service.entity.Order;
import com.ordersystem.order_service.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    public ResponseEntity<?> createOrder(@RequestBody CreateOrderRequest request) {
        if (request.isInvalid()) {
            return ResponseEntity.badRequest()
                    .body("productCode는 필수, quantity > 0, price >= 0 이어야 합니다");
        }

        Order order = orderService.createOrder(request.productCode(), request.quantity(), request.price());
        return ResponseEntity.status(HttpStatus.CREATED).body(OrderResponse.from(order));
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable Long orderId) {
        return orderService.findOrder(orderId)
                .map(order -> ResponseEntity.ok(OrderResponse.from(order)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
