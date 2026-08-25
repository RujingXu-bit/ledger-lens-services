package com.ledgerlens.analytics.service;

import com.ledgerlens.analytics.domain.PerformanceMetrics;
import java.time.Instant;

/**
 * Metrics plus the two facts a caller needs to decide whether to trust them.
 *
 * @param computedAt when these figures were calculated
 * @param stale      true when they were served from cache because
 *                   transaction-service could not be reached
 */
public record PerformanceResult(PerformanceMetrics metrics, Instant computedAt, boolean stale) {
}
