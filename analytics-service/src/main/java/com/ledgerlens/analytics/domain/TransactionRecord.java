package com.ledgerlens.analytics.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The narrow slice of a transaction this service needs.
 *
 * <p>transaction-service publishes eleven fields; five are read here. Declaring
 * the smaller view rather than mirroring the whole payload means a new field
 * upstream is not a change downstream, and it documents exactly what this
 * service depends on — which is what a consumer contract test will pin.
 *
 * @param cashAmount signed: negative when money leaves the portfolio
 */
public record TransactionRecord(
        TransactionKind type,
        String symbol,
        BigDecimal quantity,
        BigDecimal cashAmount,
        Instant executedAt) {
}
