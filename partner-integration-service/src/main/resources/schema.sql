USE partner_db;
CREATE TABLE IF NOT EXISTS inbox (
 event_id VARCHAR(64) PRIMARY KEY, run_id VARCHAR(64) NOT NULL, seller_id VARCHAR(32) NOT NULL,
 order_id BIGINT NOT NULL, seq INT NOT NULL, payload TEXT NOT NULL,
 topic VARCHAR(128) NOT NULL, partition_id INT NOT NULL, kafka_offset BIGINT NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 next_at BIGINT NOT NULL DEFAULT 0, received_at BIGINT NOT NULL, done_at BIGINT,
 UNIQUE KEY order_seq(seller_id,order_id,seq), KEY ready(state,next_at)
);
CREATE TABLE IF NOT EXISTS partner_effect (
 event_id VARCHAR(64) PRIMARY KEY, run_id VARCHAR(64) NOT NULL, seller_id VARCHAR(32) NOT NULL,
 order_id BIGINT NOT NULL, seq INT NOT NULL, operation VARCHAR(16) NOT NULL,
 quantity INT NOT NULL, price DOUBLE NOT NULL, completed_at BIGINT NOT NULL,
 UNIQUE KEY order_seq(seller_id,order_id,seq)
);
CREATE TABLE IF NOT EXISTS partner_order (
 seller_id VARCHAR(32) NOT NULL, order_id BIGINT NOT NULL, seq INT NOT NULL,
 operation VARCHAR(16) NOT NULL, quantity INT NOT NULL, price DOUBLE NOT NULL,
 PRIMARY KEY(seller_id,order_id)
);
