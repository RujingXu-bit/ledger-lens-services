package com.ledgerlens.analytics.web;

import com.ledgerlens.analytics.domain.PerformanceMetrics;
import com.ledgerlens.analytics.service.PerformanceResult;
import io.swagger.v3.oas.annotations.media.Schema;
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
        @Schema(description = "First valuation date actually used") LocalDate from,
        @Schema(description = "Last valuation date actually used") LocalDate to,
        @Schema(description = "Number of daily returns behind the statistics — one fewer than the "
                + "number of valuation days", example = "179") int observations,
        @Schema(description = "Net asset value on the first day", example = "49997.0000") BigDecimal startingValue,
        @Schema(description = "Net asset value on the last day. Includes money paid in during the "
                + "window, which is why it is not `startingValue x (1 + totalReturn)`.",
                example = "70712.2276") BigDecimal endingValue,
        @Schema(description = "Cumulative time-weighted return as a decimal fraction: 0.158612 is 15.86%",
                example = "0.158612") BigDecimal totalReturn,
        @Schema(description = "Geometric, scaled by 252 trading days", example = "0.230306") BigDecimal annualisedReturn,
        @Schema(description = "Sample standard deviation of daily returns, scaled by sqrt(252)",
                example = "0.138144") BigDecimal annualisedVolatility,
        @Schema(description = "Worst peak-to-trough fall of the return index. Zero or negative.",
                example = "-0.070159") BigDecimal maxDrawdown,
        @Schema(description = "Null when volatility is zero — undefined, not infinite",
                example = "1.667139", nullable = true) BigDecimal sharpeRatio,
        @Schema(description = "The rate used, as supplied", example = "0") BigDecimal riskFreeRate,
        @Schema(description = "Always TIME_WEIGHTED. Stated so nobody has to guess which convention "
                + "produced the number, or compare it against one that used the other.",
                example = "TIME_WEIGHTED", allowableValues = "TIME_WEIGHTED") String returnMethod,
        @Schema(description = "True when transaction-service was unreachable and this is the last "
                + "successful result. Check it before acting on the figures.", example = "false") boolean stale,
        @Schema(description = "When these figures were calculated") Instant computedAt) {

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
