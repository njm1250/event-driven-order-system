package com.ordersystem.order_service.service;

import com.ordersystem.common.events.OrderCreatedEvent;
import com.ordersystem.order_service.entity.Order;
import com.ordersystem.order_service.repository.OrderRepository;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class OrderService {

    @Autowired
    private OrderProducer orderProducer;

    @Autowired
    private OrderRepository orderRepository;

    public void createOrder(Order order) {
        Order savedOrder = orderRepository.save(order);

        OrderCreatedEvent orderCreatedEvent = OrderCreatedEvent.builder()
                .orderId(savedOrder.getOrderId())
                .productCode(savedOrder.getProductCode())
                .quantity(savedOrder.getQuantity())
                .price(savedOrder.getPrice())
                .orderStatus(savedOrder.getOrderStatus().name())
                .build();
        orderProducer.sendMessage("inventory-order-created", orderCreatedEvent);
    }
}
