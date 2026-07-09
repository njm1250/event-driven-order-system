package com.ordersystem.inventory_service.service;

import com.ordersystem.common.events.OrderCreatedEvent;
import com.ordersystem.common.events.OrderStockUpdateFailedEvent;
import com.ordersystem.common.events.OrderStockUpdatedEvent;
import com.ordersystem.common.events.Topics;
import com.ordersystem.inventory_service.entity.Inventory;
import com.ordersystem.inventory_service.entity.StockHistory;
import com.ordersystem.inventory_service.repository.InventoryRepository;
import com.ordersystem.inventory_service.repository.StockHistoryRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class InventoryConsumer {

    private final InventoryProducer inventoryProducer;
    private final InventoryRepository inventoryRepository;
    private final StockHistoryRepository stockHistoryRepository;
    private final boolean idempotencyEnabled;

    public InventoryConsumer(InventoryProducer inventoryProducer,
                             InventoryRepository inventoryRepository,
                             StockHistoryRepository stockHistoryRepository,
                             @Value("${app.idempotency-enabled:true}") boolean idempotencyEnabled) {
        this.inventoryProducer = inventoryProducer;
        this.inventoryRepository = inventoryRepository;
        this.stockHistoryRepository = stockHistoryRepository;
        this.idempotencyEnabled = idempotencyEnabled;
    }

    @KafkaListener(topics = Topics.ORDER_CREATED, groupId = "inventory-group")
    @Transactional
    public void handleOrderCreatedEvent(OrderCreatedEvent event) {
        String key = String.valueOf(event.getOrderId());

        // at-least-once 전달로 같은 이벤트가 다시 오면 처리 이력으로 판별해 건너뛴다.
        if (idempotencyEnabled && stockHistoryRepository.existsByEventId(event.getEventId())) {
            log.info("Duplicate event {} for order {}; already processed, skipping",
                    event.getEventId(), event.getOrderId());
            return;
        }

        Inventory inventory = inventoryRepository.findByProductCode(event.getProductCode()).orElse(null);
        if (inventory == null) {
            log.warn("Unknown product {} for order {}", event.getProductCode(), event.getOrderId());
            sendFailed(key, event.getOrderId(), "Product not found: " + event.getProductCode());
            return;
        }

        if (inventory.getStockQuantity() < event.getQuantity()) {
            sendFailed(key, event.getOrderId(), "Insufficient stock");
            return;
        }

        inventory.setStockQuantity(inventory.getStockQuantity() - event.getQuantity());
        stockHistoryRepository.save(StockHistory.builder()
                .eventId(event.getEventId())
                .orderId(event.getOrderId())
                .delta(-event.getQuantity())
                .build());
        // 낙관적 락 충돌은 여기서 flush로 드러나 리스너 예외로 전파된다.
        // 경합은 재고 부족이 아니므로 취소 이벤트가 아니라 컨테이너 재시도로 해소한다.
        inventoryRepository.saveAndFlush(inventory);

        inventoryProducer.sendMessage(Topics.STOCK_UPDATED, key,
                OrderStockUpdatedEvent.builder().orderId(event.getOrderId()).build());
    }

    private void sendFailed(String key, Long orderId, String reason) {
        inventoryProducer.sendMessage(Topics.STOCK_UPDATE_FAILED, key,
                OrderStockUpdateFailedEvent.builder().orderId(orderId).reason(reason).build());
    }
}
