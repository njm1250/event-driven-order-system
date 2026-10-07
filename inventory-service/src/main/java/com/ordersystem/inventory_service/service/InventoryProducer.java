package com.ordersystem.inventory_service.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ordersystem.inventory_service.outbox.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class InventoryProducer {
    private final OutboxEventRepository outbox;
    private final ObjectMapper mapper;
    public void sendMessage(String topic, String key, Object event) {
        if (outbox.lockCapacity() == null) throw new IllegalStateException("Outbox capacity row missing");
        if (outbox.count() >= 2000) throw new IllegalStateException("Inventory Outbox retained-row limit reached");
        try {
            String id = (String) event.getClass().getMethod("getEventId").invoke(event);
            outbox.save(OutboxEvent.builder().eventId(id).aggregateId(key).topic(topic)
                    .eventType(event.getClass().getName()).payload(mapper.writeValueAsString(event)).build());
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
