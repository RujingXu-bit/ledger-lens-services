package com.ledgerlens.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ledgerlens.transaction.PostgresTestcontainerConfiguration;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Persistence tests against a real Postgres.
 *
 * <p>{@code replace = NONE} is what stops Spring Boot swapping in an embedded
 * database and quietly testing something other than production.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestcontainerConfiguration.class)
class TransactionRepositoryTest {

    private static final UUID PORTFOLIO = UUID.randomUUID();
    private static final UUID OTHER_PORTFOLIO = UUID.randomUUID();

    @Autowired
    private TransactionRepository repository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void savesAndReadsBackEveryFieldIncludingScale() {
        Transaction saved = repository.saveAndFlush(Transaction.of(
                PORTFOLIO, TransactionType.BUY, "IWDA",
                new BigDecimal("10.5"), new BigDecimal("98.75"), new BigDecimal("1.50"),
                null, "EUR", Instant.parse("2026-01-15T09:00:00Z")));

        // Clear the persistence context so the assertions read from Postgres
        // rather than from Hibernate's first-level cache - otherwise this test
        // would pass even if nothing were written at all.
        entityManager.clear();

        Transaction found = repository.findById(saved.getId()).orElseThrow();
        assertThat(found.getSymbol()).isEqualTo("IWDA");
        assertThat(found.getQuantity()).isEqualByComparingTo("10.5");
        // 10.5 * 98.75 = 1036.875, plus a 1.50 fee.
        assertThat(found.getCashAmount()).isEqualByComparingTo("-1038.3750");
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    void listsOnePortfolioInExecutionOrderAndIgnoresOthers() {
        repository.save(cashDeposit(PORTFOLIO, "2026-02-01T00:00:00Z", "300"));
        repository.save(cashDeposit(PORTFOLIO, "2026-01-01T00:00:00Z", "100"));
        repository.save(cashDeposit(PORTFOLIO, "2026-01-15T00:00:00Z", "200"));
        repository.save(cashDeposit(OTHER_PORTFOLIO, "2026-01-10T00:00:00Z", "999"));
        repository.flush();

        List<Transaction> found = repository.findByPortfolioIdOrderByExecutedAtAscIdAsc(
                PORTFOLIO, PageRequest.of(0, 100));

        assertThat(found).hasSize(3);
        assertThat(found).extracting(Transaction::getCashAmount)
                .containsExactly(new BigDecimal("100.0000"), new BigDecimal("200.0000"), new BigDecimal("300.0000"));
    }

    @Test
    void rangeQueryIncludesBothEndpoints() {
        repository.save(cashDeposit(PORTFOLIO, "2026-01-01T00:00:00Z", "100"));
        repository.save(cashDeposit(PORTFOLIO, "2026-01-15T00:00:00Z", "200"));
        repository.save(cashDeposit(PORTFOLIO, "2026-02-01T00:00:00Z", "300"));
        repository.flush();

        List<Transaction> found = repository.findByPortfolioIdAndExecutedAtBetweenOrderByExecutedAtAscIdAsc(
                PORTFOLIO,
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-02-01T00:00:00Z"),
                PageRequest.of(0, 100));

        assertThat(found).hasSize(3);
    }

    /**
     * The database defends the same rules the domain does. This test writes
     * around the entity, with raw SQL, exactly as a migration script or an
     * engineer at a psql prompt would - and the CHECK constraint still holds.
     */
    @Test
    void databaseRejectsATradeWithNoSymbolEvenWhenTheDomainIsBypassed() {
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into transactions
                    (id, portfolio_id, type, symbol, quantity, price_per_unit,
                     fee, cash_amount, currency, executed_at, created_at)
                values
                    (gen_random_uuid(), ?, 'BUY', null, 1, 1, 0, -1, 'EUR', now(), now())
                """, PORTFOLIO))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("transactions_shape");
    }

    private static Transaction cashDeposit(UUID portfolioId, String executedAt, String amount) {
        return Transaction.of(portfolioId, TransactionType.DEPOSIT, null, null, null, null,
                new BigDecimal(amount), "EUR", Instant.parse(executedAt));
    }
}
