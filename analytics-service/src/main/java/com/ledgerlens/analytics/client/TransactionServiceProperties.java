package com.ledgerlens.analytics.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything about the upstream dependency, in one typed object bound from
 * configuration.
 *
 * <p>Typed properties rather than {@code @Value} strings: the timeouts are
 * {@link Duration}, so {@code 2s} in YAML is parsed and validated at startup
 * instead of being a string somebody parses wrongly at call time.
 *
 * @param baseUrl        where transaction-service lives; the only value that changes
 *                       between laptop, compose, Container Apps and Kubernetes
 * @param connectTimeout how long to wait for a TCP connection
 * @param readTimeout    how long to wait for a response once connected
 * @param maxAttempts    total attempts, including the first; 1 disables retrying
 * @param staleTolerance how old a cached result may be before it stops being served
 *                       during an outage
 * @param pageSize       transactions per request when reading a ledger. Clamped to the
 *                       500 transaction-service will serve; configurable mainly so tests
 *                       can force paging without fabricating five hundred rows.
 */
@ConfigurationProperties(prefix = "ledgerlens.transaction-service")
public record TransactionServiceProperties(
        String baseUrl,
        Duration connectTimeout,
        Duration readTimeout,
        int maxAttempts,
        Duration staleTolerance,
        int pageSize) {

    public TransactionServiceProperties {
        connectTimeout = connectTimeout == null ? Duration.ofSeconds(2) : connectTimeout;
        readTimeout = readTimeout == null ? Duration.ofSeconds(5) : readTimeout;
        maxAttempts = maxAttempts <= 0 ? 2 : maxAttempts;
        staleTolerance = staleTolerance == null ? Duration.ofMinutes(15) : staleTolerance;
        pageSize = pageSize <= 0 ? 500 : Math.min(pageSize, 500);
    }
}
