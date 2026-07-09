package com.ordersystem.order_service.service;

import com.ordersystem.common.events.OrderCreatedEvent;
import com.ordersystem.common.events.Topics;
import com.ordersystem.order_service.entity.Order;
import com.ordersystem.order_service.entity.OrderStatus;
import com.ordersystem.order_service.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderProducer orderProducer;
    private final OrderRepository orderRepository;

    public Order createOrder(String productCode, int quantity, double price) {
        Order order = Order.builder()
                .productCode(productCode)
                .quantity(quantity)
                .price(price)
                .orderStatus(OrderStatus.PENDING)
                .build();

        Order savedOrder = orderRepository.save(order);

        OrderCreatedEvent event = OrderCreatedEvent.builder()
                .orderId(savedOrder.getOrderId())
                .productCode(savedOrder.getProductCode())
                .quantity(savedOrder.getQuantity())
                .price(savedOrder.getPrice())
                .build();

        orderProducer.sendMessage(Topics.ORDER_CREATED, String.valueOf(savedOrder.getOrderId()), event);
        return savedOrder;
    }

    public Optional<Order> findOrder(Long orderId) {
        return orderRepository.findById(orderId);
    }
}
