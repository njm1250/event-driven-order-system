package com.ordersystem.inventory_service.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @org.springframework.data.jpa.repository.Query(value="SELECT id FROM outbox_capacity WHERE id=1 FOR UPDATE", nativeQuery=true)
    Integer lockCapacity();

    List<OutboxEvent> findTop100ByStatusOrderByIdAsc(OutboxEvent.Status status);
}
