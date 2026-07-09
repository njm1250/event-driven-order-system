package com.ordersystem.order_service.service;

import com.ordersystem.common.events.OrderStockUpdateFailedEvent;
import com.ordersystem.common.events.OrderStockUpdatedEvent;
import com.ordersystem.common.events.Topics;
import com.ordersystem.order_service.entity.Order;
import com.ordersystem.order_service.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class OrderConsumer {

    private final OrderRepository orderRepository;

    @KafkaListener(topics = Topics.STOCK_UPDATED, groupId = "order-group")
    @Transactional
    public void handleStockUpdatedEvent(OrderStockUpdatedEvent event) {
        Order order = orderRepository.findById(event.getOrderId()).orElse(null);
        if (order == null) {
            // 재시도해도 없는 주문은 생기지 않으므로 전파하지 않고 넘어간다
            log.warn("Order {} not found; skipping event {}", event.getOrderId(), event.getEventId());
            return;
        }

        if (!order.confirm()) {
            log.info("Order {} already {}; skipping duplicate/out-of-order event {}",
                    order.getOrderId(), order.getOrderStatus(), event.getEventId());
        }
    }

    @KafkaListener(topics = Topics.STOCK_UPDATE_FAILED, groupId = "order-group")
    @Transactional
    public void handleStockUpdateFailedEvent(OrderStockUpdateFailedEvent event) {
        Order order = orderRepository.findById(event.getOrderId()).orElse(null);
        if (order == null) {
            log.warn("Order {} not found; skipping event {}", event.getOrderId(), event.getEventId());
            return;
        }

        if (order.cancel()) {
            log.info("Order {} cancelled: {}", order.getOrderId(), event.getReason());
        } else {
            log.info("Order {} already {}; skipping duplicate/out-of-order event {}",
                    order.getOrderId(), order.getOrderStatus(), event.getEventId());
        }
    }
}
