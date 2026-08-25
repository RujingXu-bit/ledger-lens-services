package com.ledgerlens.analytics.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The last successful answer for each question asked, kept so that an outage
 * upstream degrades this service rather than stopping it.
 *
 * <p>This does not make analytics-service stateful in the sense that matters.
 * The cache owns nothing: every entry is reproducible from transaction-service,
 * losing it costs one recomputation, and no instance needs to agree with any
 * other. That is the difference between a cache and a database, and it is why
 * this can still be scaled and restarted freely.
 *
 * <p>A bounded LRU map rather than Caffeine or Redis. Fifteen lines with no new
 * dependency is the right size here; a shared cache would be worth it only once
 * there are enough instances that each one warming separately actually costs
 * something.
 */
@Component
public class MetricsCache {

    private static final int MAX_ENTRIES = 1_000;

    private final Clock clock;
    private final Map<Key, PerformanceResult> entries;

    public MetricsCache(Clock clock) {
        this.clock = clock;
        this.entries = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, PerformanceResult> eldest) {
                return size() > MAX_ENTRIES;
            }
        });
    }

    public void put(Key key, PerformanceResult result) {
        entries.put(key, result);
    }

    /**
     * @param tolerance how old an entry may be and still be worth serving. Past
     *                  it, an error is more honest than a number: a figure from
     *                  an hour ago presented as current is a lie the caller
     *                  cannot detect.
     */
    public Optional<PerformanceResult> find(Key key, Duration tolerance) {
        PerformanceResult cached = entries.get(key);
        if (cached == null) {
            return Optional.empty();
        }
        Instant now = Instant.now(clock);
        if (cached.computedAt().plus(tolerance).isBefore(now)) {
            return Optional.empty();
        }
        return Optional.of(new PerformanceResult(cached.metrics(), cached.computedAt(), true));
    }

    /** The whole question, so that a different window or rate is a different answer. */
    public record Key(UUID portfolioId, LocalDate from, LocalDate to, BigDecimal riskFreeRate) {
    }
}
