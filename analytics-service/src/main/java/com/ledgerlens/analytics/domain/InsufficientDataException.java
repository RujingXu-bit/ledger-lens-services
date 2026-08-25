package com.ledgerlens.analytics.domain;

/**
 * Thrown when there are too few observations for a statistic to mean anything —
 * a volatility from one return, a drawdown from one day.
 *
 * <p>Returning zero or NaN instead would put a number on a chart that no data
 * supports.
 */
public class InsufficientDataException extends RuntimeException {

    public InsufficientDataException(String message) {
        super(message);
    }
}
