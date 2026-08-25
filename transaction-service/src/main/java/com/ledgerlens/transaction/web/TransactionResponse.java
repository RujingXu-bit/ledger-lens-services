package com.ledgerlens.transaction.web;

import com.ledgerlens.transaction.domain.Transaction;
import com.ledgerlens.transaction.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The published shape of a transaction. This record is the API contract, and
 * from day 5 it is also what analytics-service parses, so removing or renaming
 * a field here breaks another service.
 */
public record TransactionResponse(
        UUID id,
        UUID portfolioId,
        TransactionType type,
        String symbol,
        BigDecimal quantity,
        BigDecimal pricePerUnit,
        BigDecimal fee,
        BigDecimal cashAmount,
        String currency,
        Instant executedAt,
        Instant createdAt
) {

    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(
                t.getId(),
                t.getPortfolioId(),
                t.getType(),
                t.getSymbol(),
                t.getQuantity(),
                t.getPricePerUnit(),
                t.getFee(),
                t.getCashAmount(),
                t.getCurrency(),
                t.getExecutedAt(),
                t.getCreatedAt());
    }
}
