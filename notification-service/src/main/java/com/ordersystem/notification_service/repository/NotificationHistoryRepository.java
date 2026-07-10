package com.ordersystem.notification_service.repository;

import com.ordersystem.notification_service.entity.NotificationHistory;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationHistoryRepository extends JpaRepository<NotificationHistory, Long> {

    boolean existsByEventId(String eventId);
}
