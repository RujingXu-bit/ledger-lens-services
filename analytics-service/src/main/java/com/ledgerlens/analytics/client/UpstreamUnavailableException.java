package com.ledgerlens.analytics.client;

/**
 * transaction-service could not be reached, or answered with a server error.
 *
 * <p>Named for the situation rather than the exception that caused it, because
 * the caller's decision is the same whether the connection was refused, the read
 * timed out, or a 503 came back: there is no data, and the question is whether
 * something stale will do instead.
 */
public class UpstreamUnavailableException extends RuntimeException {

    public UpstreamUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public UpstreamUnavailableException(String message) {
        super(message);
    }
}
