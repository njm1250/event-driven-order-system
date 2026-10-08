USE partner_db;
-- state: PENDING, CLAIMED (owner holds a lease until lease_until, DB clock in ms) or DONE.
-- generation counts claims; with owner it is the token that later writes must still match.
-- attempts counts claims that could start a partner call, so a retry is known after a crash.
CREATE TABLE IF NOT EXISTS inbox (
 event_id VARCHAR(64) PRIMARY KEY, run_id VARCHAR(64) NOT NULL, seller_id VARCHAR(32) NOT NULL,
 order_id BIGINT NOT NULL, seq INT NOT NULL, payload TEXT NOT NULL,
 topic VARCHAR(128) NOT NULL, partition_id INT NOT NULL, kafka_offset BIGINT NOT NULL,
 state VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 next_at BIGINT NOT NULL DEFAULT 0, received_at BIGINT NOT NULL, done_at BIGINT,
 owner VARCHAR(64), generation BIGINT NOT NULL DEFAULT 0, lease_until BIGINT NOT NULL DEFAULT 0,
 UNIQUE KEY order_seq(seller_id,order_id,seq), KEY ready(state,next_at), KEY by_seller(state,seller_id,received_at)
);
-- One row serializes the capacity check of every ingesting instance.
CREATE TABLE IF NOT EXISTS inbox_capacity (id INT PRIMARY KEY);
INSERT IGNORE INTO inbox_capacity VALUES (1);
-- Per-seller execution slots shared by all instances; a slot is held under the same lease as its work.
CREATE TABLE IF NOT EXISTS seller_permit (
 seller_id VARCHAR(32) NOT NULL, slot INT NOT NULL, owner VARCHAR(64), event_id VARCHAR(64),
 generation BIGINT NOT NULL DEFAULT 0, lease_until BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(seller_id,slot)
);
-- Retry admissions per seller for the rolling budget shared by all instances.
CREATE TABLE IF NOT EXISTS retry_admission (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, seller_id VARCHAR(32) NOT NULL, event_id VARCHAR(64) NOT NULL,
 admitted_at BIGINT NOT NULL, KEY by_seller(seller_id,admitted_at)
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
-- Completion reports for the source, written in the delivery transaction and relayed to Kafka by
-- the instance that wrote them (owner), or by any instance once they are old enough to be orphaned.
CREATE TABLE IF NOT EXISTS completion_outbox (
 id BIGINT AUTO_INCREMENT PRIMARY KEY, event_id VARCHAR(64) NOT NULL UNIQUE, seller_id VARCHAR(32) NOT NULL,
 order_id BIGINT NOT NULL, payload TEXT NOT NULL, owner VARCHAR(64) NOT NULL, created_at BIGINT NOT NULL, sent_at BIGINT,
 KEY unsent(sent_at,id)
);
