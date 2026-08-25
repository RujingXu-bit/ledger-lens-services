package com.ledgerlens.transaction;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Smoke test: the Spring context starts and the JPA layer connects.
 *
 * <p>The database is a real Postgres in a throwaway container, started by
 * Testcontainers and thrown away afterwards. {@code @ServiceConnection} is the
 * part worth remembering: Spring Boot reads the container's host, port,
 * username and password and overrides {@code spring.datasource.*} with them, so
 * there is no duplicated JDBC URL anywhere in the test code.
 */
@SpringBootTest
@Testcontainers
class TransactionServiceApplicationTests {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void contextLoads() {
        // Failing here means the wiring is broken before a single line of
        // domain code exists - which is exactly what a skeleton should prove.
    }
}
