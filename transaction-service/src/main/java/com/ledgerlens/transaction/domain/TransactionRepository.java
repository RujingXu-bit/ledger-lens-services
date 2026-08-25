package com.ledgerlens.transaction.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /**
     * Positions as at a moment in time, summed straight out of the ledger:
     * buys add, sells subtract, everything else is cash and does not move a
     * position. Fully closed positions are dropped by the HAVING clause -
     * holding zero of something is not holding it.
     *
     * <p>Aggregating in the database rather than loading every transaction and
     * folding it in Java is the point. Postgres returns one row per symbol; the
     * alternative ships the whole ledger over the wire to compute a number the
     * database could have computed in place.
     */
    @Query("""
            select new com.ledgerlens.transaction.domain.Holding(
                       t.symbol,
                       sum(case when t.type = :buy then t.quantity else -t.quantity end))
            from Transaction t
            where t.portfolioId = :portfolioId
              and t.type in (:buy, :sell)
              and t.executedAt <= :asOf
            group by t.symbol
            having sum(case when t.type = :buy then t.quantity else -t.quantity end) <> 0
            order by t.symbol
            """)
    List<Holding> findHoldings(@Param("portfolioId") UUID portfolioId,
                               @Param("asOf") Instant asOf,
                               @Param("buy") TransactionType buy,
                               @Param("sell") TransactionType sell);

    /**
     * The enum constants are passed as parameters rather than written as JPQL
     * literals, and hidden behind this default method so callers never see it.
     */
    default List<Holding> findHoldingsAsOf(UUID portfolioId, Instant asOf) {
        return findHoldings(portfolioId, asOf, TransactionType.BUY, TransactionType.SELL);
    }
}
