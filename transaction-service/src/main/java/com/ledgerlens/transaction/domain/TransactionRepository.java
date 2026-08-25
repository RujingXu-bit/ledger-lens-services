package com.ledgerlens.transaction.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data writes the implementation from the method names at startup.
 *
 * <p>Every finder is scoped by {@code portfolioId}. There is no "find all
 * transactions" on purpose — an endpoint that can return the whole ledger is an
 * endpoint that will one day be asked to.
 */
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    /**
     * Ordered by execution time, then id. The tiebreaker is not decoration:
     * without it two transactions with the same timestamp come back in
     * whatever order Postgres finds convenient, which makes paging skip and
     * repeat rows, and makes analytics results irreproducible.
     */
    List<Transaction> findByPortfolioIdOrderByExecutedAtAscIdAsc(UUID portfolioId, Pageable pageable);

    List<Transaction> findByPortfolioIdAndExecutedAtBetweenOrderByExecutedAtAscIdAsc(
            UUID portfolioId, Instant from, Instant to, Pageable pageable);
}
