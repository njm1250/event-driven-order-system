package com.ordersystem.order_service.service;

import com.ordersystem.common.events.OrderCreatedEvent;
import com.ordersystem.order_service.entity.Order;
import com.ordersystem.order_service.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderProducer orderProducer;
    private final OrderRepository orderRepository;

    public void processCreateOrder(Order order) {
        Order savedOrder = orderRepository.save(order);

        OrderCreatedEvent event = OrderCreatedEvent.builder()
                .orderId(savedOrder.getOrderId())
                .productCode(savedOrder.getProductCode())
                .quantity(savedOrder.getQuantity())
                .price(savedOrder.getPrice())
                .orderStatus(savedOrder.getOrderStatus().name())
                .build();

        orderProducer.sendMessage("inventory-order-created", event);
    }
}
