-- Version 003: per-seller admission for delivery obligations (load experiments).
USE order_db;
CREATE TABLE IF NOT EXISTS seller_quota (
  seller_id VARCHAR(32) PRIMARY KEY, used INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX obligation_order ON delivery_obligation(seller_id, order_id, seq);
