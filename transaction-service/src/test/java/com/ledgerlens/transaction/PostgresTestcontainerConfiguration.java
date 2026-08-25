package com.ledgerlens.transaction;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One Postgres container, shared by every test class that imports this.
 *
 * <p>Declaring the container as a bean rather than a {@code @Container} field
 * matters for build time: Spring caches test contexts by configuration, so all
 * importing classes reuse the same context and therefore the same container.
 * A static {@code @Container} per test class starts Postgres once per class,
 * and that is most of the runtime of a test suite that has any size to it.
 *
 * <p>{@code @ServiceConnection} hands Spring Boot the container's host, port,
 * database, username and password, so no JDBC URL is repeated in test code.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainerConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }
}
