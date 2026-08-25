package com.ledgerlens.analytics.service;

import java.util.UUID;

/**
 * transaction-service answered, and the portfolio has no transactions at all.
 *
 * <p>Distinct from the upstream being unavailable: this is a definite answer
 * that there is nothing to measure, not a failure to get an answer. Conflating
 * the two would tell a caller their portfolio is empty when in fact the system
 * of record is down.
 */
public class PortfolioNotFoundException extends RuntimeException {

    public PortfolioNotFoundException(UUID portfolioId) {
        super("No transactions found for portfolio " + portfolioId);
    }
}
