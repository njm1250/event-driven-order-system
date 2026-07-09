package com.ordersystem.order_service.service;

import com.ordersystem.common.events.OrderStockUpdateFailedEvent;
import com.ordersystem.common.events.OrderStockUpdatedEvent;
import com.ordersystem.common.events.Topics;
import com.ordersystem.order_service.entity.Order;
import com.ordersystem.order_service.entity.OrderStatus;
import com.ordersystem.order_service.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderConsumer {

    private final OrderRepository orderRepository;

    @KafkaListener(topics = Topics.STOCK_UPDATED, groupId = "order-group")
    public void handleStockUpdatedEvent(OrderStockUpdatedEvent event) {
        Order order = orderRepository.findById(event.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + event.getOrderId()));

        order.setOrderStatus(OrderStatus.CONFIRMED);
        orderRepository.save(order);
    }

    @KafkaListener(topics = Topics.STOCK_UPDATE_FAILED, groupId = "order-group")
    public void handleStockUpdateFailedEvent(OrderStockUpdateFailedEvent event) {
        Order order = orderRepository.findById(event.getOrderId())
                .orElseThrow(() -> new IllegalStateException("Order not found: " + event.getOrderId()));

        log.info("Order {} cancelled: {}", event.getOrderId(), event.getReason());

        order.setOrderStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
    }
}
