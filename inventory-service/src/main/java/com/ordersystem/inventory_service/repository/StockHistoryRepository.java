package com.ordersystem.inventory_service.repository;

import com.ordersystem.inventory_service.entity.StockHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockHistoryRepository extends JpaRepository<StockHistory, Long> {

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update StockHistory s set s.delta=:delta where s.eventId=:eventId")
    void updateDelta(@org.springframework.data.repository.query.Param("eventId") String eventId,
                     @org.springframework.data.repository.query.Param("delta") int delta);

    boolean existsByEventId(String eventId);
}
