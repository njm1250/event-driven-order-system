-- Version 001: explicit experiment schema; fresh databases only.
USE order_db;
CREATE TABLE IF NOT EXISTS `orders` (
  `order_id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `order_status` enum('CANCELLED','CONFIRMED','PENDING','SHIPPED') DEFAULT NULL,
  `partner_sequence` int NOT NULL,
  `price` double NOT NULL,
  `product_cd` varchar(255) DEFAULT NULL,
  `quantity` int NOT NULL,
  `run_id` varchar(255) DEFAULT NULL,
  `seller_id` varchar(255) NOT NULL,
  `updated_at` datetime(6) DEFAULT NULL,
  `version` bigint DEFAULT NULL,
  PRIMARY KEY (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `outbox_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `aggregate_id` varchar(255) NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `event_id` varchar(36) NOT NULL,
  `event_type` varchar(255) NOT NULL,
  `payload` text NOT NULL,
  `sent_at` datetime(6) DEFAULT NULL,
  `status` enum('PENDING','SENT') NOT NULL,
  `topic` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_c7pmgf9c7t5nt657in8sec87v` (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
USE inventory_db;
CREATE TABLE IF NOT EXISTS `inventories` (
  `inventory_id` bigint NOT NULL AUTO_INCREMENT,
  `product_cd` varchar(255) DEFAULT NULL,
  `stock_quantity` int DEFAULT NULL,
  `version` int DEFAULT NULL,
  PRIMARY KEY (`inventory_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `stock_history` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `created_at` datetime(6) DEFAULT NULL,
  `delta` int NOT NULL,
  `event_id` varchar(36) NOT NULL,
  `order_id` bigint NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_9n5mw6d3o4xpiejhcwma5q7xe` (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE TABLE IF NOT EXISTS `outbox_event` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `aggregate_id` varchar(255) NOT NULL,
  `created_at` datetime(6) DEFAULT NULL,
  `event_id` varchar(36) NOT NULL,
  `event_type` varchar(255) NOT NULL,
  `payload` text NOT NULL,
  `sent_at` datetime(6) DEFAULT NULL,
  `status` enum('PENDING','SENT') NOT NULL,
  `topic` varchar(255) NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `UK_c7pmgf9c7t5nt657in8sec87v` (`event_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
USE order_db;
CREATE TABLE IF NOT EXISTS outbox_capacity(id INT PRIMARY KEY);
INSERT IGNORE INTO outbox_capacity VALUES(1);
USE inventory_db;
CREATE TABLE IF NOT EXISTS outbox_capacity(id INT PRIMARY KEY);
INSERT IGNORE INTO outbox_capacity VALUES(1);
