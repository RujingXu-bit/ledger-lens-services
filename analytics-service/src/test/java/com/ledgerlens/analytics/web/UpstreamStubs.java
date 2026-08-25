package com.ledgerlens.analytics.web;

import java.util.UUID;

/**
 * The JSON transaction-service actually returns, written out longhand rather
 * than generated from the other module's classes.
 *
 * <p>Sharing the real DTOs would make these tests pass whenever both services
 * were wrong in the same way. A hand-written fixture is a statement about the
 * contract as published, which is what a consumer is entitled to rely on.
 */
final class UpstreamStubs {

    private UpstreamStubs() {
    }

    /** Includes fields analytics-service does not read, exactly as the real payload does. */
    static String transaction(UUID portfolioId, String type, String symbol,
                              String quantity, String cashAmount, String executedAt) {
        return """
                {
                  "id": "%s",
                  "portfolioId": "%s",
                  "type": "%s",
                  "symbol": %s,
                  "quantity": %s,
                  "pricePerUnit": 100.00000000,
                  "fee": 0.0000,
                  "cashAmount": %s,
                  "currency": "EUR",
                  "executedAt": "%s",
                  "createdAt": "2026-01-05T10:00:00Z"
                }
                """.formatted(UUID.randomUUID(), portfolioId, type,
                symbol == null ? "null" : "\"" + symbol + "\"",
                quantity == null ? "null" : quantity,
                cashAmount, executedAt);
    }

    static String price(String symbol, String date, String close) {
        return """
                {"symbol":"%s","priceDate":"%s","closePrice":%s,"currency":"EUR"}
                """.formatted(symbol, date, close);
    }

    /**
     * A deposit of 1,000 that immediately buys 10 units at 100, then three
     * closes: 100, 110, 99.
     *
     * <p>Chain-linked that is (1 + 0.10)(1 - 0.10) - 1 = -1%, which is the
     * number the assertions look for. Worked out by hand, not by running the
     * code.
     */
    static String ledger(UUID portfolioId) {
        return "[" + transaction(portfolioId, "DEPOSIT", null, null, "1000.0000", "2026-01-05T09:00:00Z")
                + "," + transaction(portfolioId, "BUY", "ACME", "10.00000000", "-1000.0000", "2026-01-05T09:00:00Z")
                + "]";
    }

    static String prices() {
        return "[" + price("ACME", "2026-01-05", "100.00000000")
                + "," + price("ACME", "2026-01-06", "110.00000000")
                + "," + price("ACME", "2026-01-07", "99.00000000")
                + "]";
    }
}
