package com.ledgerlens.analytics.domain;

import java.time.LocalDate;

/**
 * Thrown when a position is held on a day for which no price is known, at or
 * before that day.
 *
 * <p>Failing loudly is the point. The alternatives are to value the position at
 * zero, which shows a catastrophic loss that never happened, or to skip it,
 * which quietly understates the portfolio. A wrong number that looks plausible
 * is worse than an error, because nobody investigates it.
 */
public class MissingPriceException extends RuntimeException {

    public MissingPriceException(String symbol, LocalDate date) {
        super("No price for %s on or before %s".formatted(symbol, date));
    }
}
