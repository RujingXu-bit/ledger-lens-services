package com.ledgerlens.analytics.client;

/**
 * transaction-service answered, and its answer was that our request was wrong —
 * a 4xx.
 *
 * <p>Deliberately not an {@link UpstreamUnavailableException}. That one means
 * "no answer was available, something stale may do instead"; this one means the
 * upstream is healthy and this service asked it the wrong question. Retrying is
 * pointless and serving a cached result would hide a bug, so neither happens.
 *
 * <p>In practice this is a misconfigured base URL, a parameter this client
 * sends that the producer no longer accepts, or credentials that were never
 * set up. All of them are faults on this side of the boundary.
 */
public class UpstreamRejectedException extends RuntimeException {

    private final int statusCode;

    public UpstreamRejectedException(int statusCode, String what) {
        super("transaction-service rejected the request for " + what + " with status " + statusCode);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
