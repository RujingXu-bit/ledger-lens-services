package com.ledgerlens.transaction.service;

import com.ledgerlens.transaction.domain.Transaction;
import com.ledgerlens.transaction.domain.TransactionNotFoundException;
import com.ledgerlens.transaction.domain.TransactionRepository;
import com.ledgerlens.transaction.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The application layer: it orchestrates, it does not decide.
 *
 * <p>What may exist is decided by {@link Transaction#of}; how it is stored is
 * decided by the repository. This class owns transaction boundaries and
 * nothing else, which is why it stays this short.
 */
@Service
@Transactional(readOnly = true)
public class TransactionService {

    /**
     * Stands in for "no upper bound". Not {@link Instant#MAX}: that is year one
     * billion, and Postgres rejects any timestamp past 294276 AD, so passing it
     * turns an open-ended query into a driver-level error.
     */
    private static final Instant OPEN_ENDED = Instant.parse("9999-12-31T23:59:59Z");

    private final TransactionRepository repository;

    public TransactionService(TransactionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Transaction record(UUID portfolioId,
                              TransactionType type,
                              String symbol,
                              BigDecimal quantity,
                              BigDecimal pricePerUnit,
                              BigDecimal fee,
                              BigDecimal amount,
                              String currency,
                              Instant executedAt) {
        Transaction transaction = Transaction.of(
                portfolioId, type, symbol, quantity, pricePerUnit, fee, amount, currency, executedAt);
        return repository.save(transaction);
    }

    public Transaction findById(UUID id) {
        return repository.findById(id).orElseThrow(() -> new TransactionNotFoundException(id));
    }

    /**
     * The read analytics-service will live on from day 5. {@code from} and
     * {@code to} are both inclusive; omitting them means the whole history.
     */
    public List<Transaction> findForPortfolio(UUID portfolioId, Instant from, Instant to, Pageable pageable) {
        if (from == null && to == null) {
            return repository.findByPortfolioIdOrderByExecutedAtAscIdAsc(portfolioId, pageable);
        }
        Instant lowerBound = from == null ? Instant.EPOCH : from;
        Instant upperBound = to == null ? OPEN_ENDED : to;
        return repository.findByPortfolioIdAndExecutedAtBetweenOrderByExecutedAtAscIdAsc(
                portfolioId, lowerBound, upperBound, pageable);
    }
}
