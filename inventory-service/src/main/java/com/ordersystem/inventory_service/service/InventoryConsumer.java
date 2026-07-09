package com.ordersystem.inventory_service.service;

import com.ordersystem.common.events.OrderCreatedEvent;
import com.ordersystem.common.events.OrderStockUpdateFailedEvent;
import com.ordersystem.common.events.OrderStockUpdatedEvent;
import com.ordersystem.inventory_service.entity.Inventory;
import com.ordersystem.inventory_service.repository.InventoryRepository;
import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class InventoryConsumer {

    private final InventoryProducer inventoryProducer;
    private final InventoryRepository inventoryRepository;

    @KafkaListener(topics = "inventory-order-created", groupId = "inventory-group")
    public void handleOrderCreatedEvent(OrderCreatedEvent event) {
        Optional<Inventory> inventoryOpt = inventoryRepository.findByProductCode(event.getProductCode());

        inventoryOpt.ifPresent(inventory -> {
            if (inventory.getStockQuantity() >= event.getQuantity()) {
                inventory.setStockQuantity(inventory.getStockQuantity() - event.getQuantity());
                try {
                    inventoryRepository.save(inventory);
                    inventoryProducer.sendMessage("order-stock-update", new OrderStockUpdatedEvent(event.getOrderId()));
                } catch (OptimisticLockException e) {
                    inventoryProducer.sendMessage("order-stock-update-failed",
                            new OrderStockUpdateFailedEvent(event.getOrderId(), "Insufficient stock available"));
                }
            } else {
                inventoryProducer.sendMessage("order-stock-update-failed",
                        new OrderStockUpdateFailedEvent(event.getOrderId(), "Product out of stock"));
            }
        });
    }
}
