package com.ledgerlens.analytics.web;

import com.ledgerlens.analytics.domain.PerformanceMetrics;
import com.ledgerlens.analytics.service.PerformanceResult;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The published shape of a performance report.
 *
 * <p>{@code stale} and {@code computedAt} are part of the contract, not
 * debugging extras. A caller that cannot tell a live figure from a cached one
 * has no way to decide whether to act on it.
 */
public record PerformanceResponse(
        UUID portfolioId,
        LocalDate from,
        LocalDate to,
        int observations,
        BigDecimal startingValue,
        BigDecimal endingValue,
        BigDecimal totalReturn,
        BigDecimal annualisedReturn,
        BigDecimal annualisedVolatility,
        BigDecimal maxDrawdown,
        BigDecimal sharpeRatio,
        BigDecimal riskFreeRate,
        String returnMethod,
        boolean stale,
        Instant computedAt) {

    public static PerformanceResponse from(UUID portfolioId, PerformanceResult result) {
        PerformanceMetrics metrics = result.metrics();
        return new PerformanceResponse(
                portfolioId,
                metrics.from(),
                metrics.to(),
                metrics.observations(),
                metrics.startingValue(),
                metrics.endingValue(),
                metrics.totalReturn(),
                metrics.annualisedReturn(),
                metrics.annualisedVolatility(),
                metrics.maxDrawdown(),
                metrics.sharpeRatio(),
                metrics.riskFreeRate(),
                // Stated in the payload so nobody has to guess which convention
                // produced the number, or compare it against one that used the other.
                "TIME_WEIGHTED",
                result.stale(),
                result.computedAt());
    }
}
