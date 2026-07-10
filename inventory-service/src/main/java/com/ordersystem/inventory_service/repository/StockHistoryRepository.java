package com.ordersystem.inventory_service.repository;

import com.ordersystem.inventory_service.entity.StockHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockHistoryRepository extends JpaRepository<StockHistory, Long> {

    boolean existsByEventId(String eventId);
}
