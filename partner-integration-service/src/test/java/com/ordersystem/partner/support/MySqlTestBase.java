package com.ordersystem.partner.support;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;

/** Real MySQL 8.4 with the production schema; tables are emptied before each test. */
public abstract class MySqlTestBase {
    @SuppressWarnings("resource")
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withDatabaseName("partner_db");
    private static HikariDataSource dataSource;
    protected static JdbcTemplate db;
    protected static TransactionTemplate tx;

    @BeforeAll
    static void startDatabase() {
        if (!MYSQL.isRunning()) MYSQL.start();
        if (dataSource == null) {
            dataSource = new HikariDataSource();
            dataSource.setJdbcUrl(MYSQL.getJdbcUrl());
            dataSource.setUsername(MYSQL.getUsername());
            dataSource.setPassword(MYSQL.getPassword());
            dataSource.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
            new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
            db = new JdbcTemplate(dataSource);
            tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        }
    }

    @BeforeEach
    void emptyTables() {
        db.update("DELETE FROM inbox");
        db.update("DELETE FROM partner_effect");
        db.update("DELETE FROM partner_order");
        db.update("DELETE FROM seller_permit");
        db.update("DELETE FROM retry_admission");
        db.update("DELETE FROM completion_outbox");
    }

    @AfterAll
    static void keepContainerForOtherClasses() {
        // Testcontainers' Ryuk removes the container when the JVM exits.
    }
}
