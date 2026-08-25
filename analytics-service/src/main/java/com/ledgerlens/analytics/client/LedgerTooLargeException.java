package com.ledgerlens.analytics.client;

import java.util.UUID;

/**
 * A portfolio with more transactions than this service is willing to pull into
 * memory in one request.
 *
 * <p>The alternative is the thing this exists to prevent: reading as much as
 * fits and reporting performance figures computed from part of a ledger, with
 * nothing in the response to say so. A refusal is recoverable — the caller can
 * narrow the window. A plausible wrong number is not, because nobody
 * investigates it.
 */
public class LedgerTooLargeException extends RuntimeException {

    public LedgerTooLargeException(UUID portfolioId, int limit) {
        super("Portfolio %s has more than %d transactions; narrow the window with from/to"
                .formatted(portfolioId, limit));
    }
}
