package com.ledgerlens.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Smoke test: the context starts, Flyway has migrated, and Hibernate's
 * {@code ddl-auto: validate} agrees that the entities match the schema.
 *
 * <p>That last part is the value here. This test fails the moment an entity
 * field and a migration disagree, which is the single most common way a JPA
 * service breaks between "compiles" and "starts in production".
 */
@SpringBootTest
@Import(PostgresTestcontainerConfiguration.class)
class TransactionServiceApplicationTests {

    @Autowired
    private DataSource dataSource;

    @Test
    void contextLoadsAndSchemaIsMigrated() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Integer migrationsApplied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success = true", Integer.class);
        assertThat(migrationsApplied).isGreaterThanOrEqualTo(1);

        Integer transactionsTable = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_name = 'transactions'", Integer.class);
        assertThat(transactionsTable).isEqualTo(1);
    }
}
