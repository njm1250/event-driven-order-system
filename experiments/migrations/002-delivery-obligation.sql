-- Version 002: delivery obligations for the load experiments; the relay reads pending rows by status.
USE order_db;
CREATE TABLE IF NOT EXISTS delivery_obligation (
  event_id VARCHAR(64) PRIMARY KEY, run_id VARCHAR(64) NOT NULL, seller_id VARCHAR(32) NOT NULL,
  order_id BIGINT NOT NULL, seq INT NOT NULL, operation VARCHAR(16) NOT NULL,
  created_at BIGINT NOT NULL, resolved_at BIGINT, completed_at BIGINT,
  KEY unresolved(resolved_at, seller_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
CREATE INDEX outbox_pending ON outbox_event(status, id);
