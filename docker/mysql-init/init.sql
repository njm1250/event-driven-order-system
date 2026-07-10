-- 서비스별 데이터베이스. named volume이 비어 있을 때 한 번만 실행된다.
CREATE DATABASE IF NOT EXISTS order_db;
CREATE DATABASE IF NOT EXISTS inventory_db;
CREATE DATABASE IF NOT EXISTS notification_db;
