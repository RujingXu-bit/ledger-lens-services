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

    @Test
    void holdingsAreTheNetOfBuysAndSells() {
        repository.save(trade(PORTFOLIO, TransactionType.BUY, "IWDA", "10", "2026-01-05T00:00:00Z"));
        repository.save(trade(PORTFOLIO, TransactionType.BUY, "IWDA", "5", "2026-01-10T00:00:00Z"));
        repository.save(trade(PORTFOLIO, TransactionType.SELL, "IWDA", "4", "2026-01-20T00:00:00Z"));
        repository.save(trade(PORTFOLIO, TransactionType.BUY, "VWCE", "2", "2026-01-12T00:00:00Z"));
        // Cash movements must not appear as positions.
        repository.save(cashDeposit(PORTFOLIO, "2026-01-01T00:00:00Z", "5000"));
        repository.flush();

        List<Holding> holdings = repository.findHoldingsAsOf(PORTFOLIO, Instant.parse("2026-12-31T00:00:00Z"));

        assertThat(holdings).extracting(Holding::symbol).containsExactly("IWDA", "VWCE");
        assertThat(holdings.get(0).quantity()).isEqualByComparingTo("11");
        assertThat(holdings.get(1).quantity()).isEqualByComparingTo("2");
    }

    @Test
    void aFullyClosedPositionIsNotAHolding() {
        repository.save(trade(PORTFOLIO, TransactionType.BUY, "ACME", "10", "2026-01-05T00:00:00Z"));
        repository.save(trade(PORTFOLIO, TransactionType.SELL, "ACME", "10", "2026-01-06T00:00:00Z"));
        repository.flush();

        assertThat(repository.findHoldingsAsOf(PORTFOLIO, Instant.parse("2026-12-31T00:00:00Z"))).isEmpty();
    }

    @Test
    void holdingsAreAsAtAPointInTimeAndIgnoreLaterTrades() {
        repository.save(trade(PORTFOLIO, TransactionType.BUY, "IWDA", "10", "2026-01-05T00:00:00Z"));
        repository.save(trade(PORTFOLIO, TransactionType.SELL, "IWDA", "4", "2026-06-01T00:00:00Z"));
        repository.flush();

        List<Holding> inMarch = repository.findHoldingsAsOf(PORTFOLIO, Instant.parse("2026-03-01T00:00:00Z"));

        assertThat(inMarch).singleElement()
                .satisfies(h -> assertThat(h.quantity()).isEqualByComparingTo("10"));
    }

    private static Transaction trade(UUID portfolioId, TransactionType type, String symbol,
                                     String quantity, String executedAt) {
        return Transaction.of(portfolioId, type, symbol, new BigDecimal(quantity), new BigDecimal("100"),
                null, null, "EUR", Instant.parse(executedAt));
    }

    private static Transaction cashDeposit(UUID portfolioId, String executedAt, String amount) {
        return Transaction.of(portfolioId, TransactionType.DEPOSIT, null, null, null, null,
                new BigDecimal(amount), "EUR", Instant.parse(executedAt));
    }
}
