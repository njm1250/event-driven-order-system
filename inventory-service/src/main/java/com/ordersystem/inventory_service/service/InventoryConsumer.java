package com.ordersystem.inventory_service.service;

import com.ordersystem.common.events.OrderCreatedEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class InventoryConsumer {

    @KafkaListener(topics = "inventory-order-created", groupId = "inventory-group")
    public void handleOrderCreatedEvent(OrderCreatedEvent event) {
        System.out.println("Received event: " + event.getOrderId());
    }
}
