package com.ledgerlens.analytics.web;

import com.ledgerlens.analytics.service.PerformanceResult;
import com.ledgerlens.analytics.service.PerformanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/portfolios/{portfolioId}/performance", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@Tag(name = "Performance")
public class PerformanceController {

    private final PerformanceService service;

    public PerformanceController(PerformanceService service) {
        this.service = service;
    }

    /**
     * @param from         inclusive ISO date; defaults to the first transaction
     * @param to           inclusive ISO date; defaults to today
     * @param riskFreeRate annualised, as a decimal — 0.025 is 2.5%. Defaults to zero,
     *                     and is echoed back in the response so the assumption behind
     *                     the Sharpe ratio travels with the number.
     */
    @Operation(
            summary = "Measure a portfolio's performance over a window",
            description = """
                    Fetches the ledger and the price history from transaction-service, rebuilds the
                    daily net asset value, and derives all four figures from one daily return series.

                    Nothing is stored. Two calls with the same arguments give the same answer unless
                    a transaction or a price changed in between — or unless the second one was served
                    from the fallback cache, in which case `stale` is `true`.

                    **Reading the response:** `endingValue` growing faster than `totalReturn` is normal
                    and is the point of the time-weighted method — the difference is money paid in.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200",
                    description = "Measured. May be a cached result — check `stale`."),
            @ApiResponse(responseCode = "404",
                    description = "The portfolio has no transactions. A definite answer, never returned "
                            + "because an upstream was unreachable.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
            @ApiResponse(responseCode = "422",
                    description = "Not enough valuation points to compute a statistic, a position held on "
                            + "a day with no known price, or a ledger too large to read in one request.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
            @ApiResponse(responseCode = "502",
                    description = "transaction-service rejected this service's request — a misconfigured "
                            + "base URL, a parameter it no longer accepts, or missing credentials. A fault "
                            + "in the integration, not in the request you made. Retrying will not help.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
            @ApiResponse(responseCode = "503",
                    description = "transaction-service could not be reached and no recent result was "
                            + "available to fall back on. Carries `Retry-After`.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    })
    @GetMapping
    public ResponseEntity<PerformanceResponse> performance(
            @PathVariable UUID portfolioId,
            @Parameter(description = "Inclusive start. Defaults to the date of the first transaction.",
                    example = "2026-01-01")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Inclusive end. Defaults to today.", example = "2026-08-25")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @Parameter(description = "Annualised, as a decimal fraction: 0.025 is 2.5%. Defaults to zero, "
                    + "and is echoed back so the assumption travels with the Sharpe ratio.",
                    example = "0.025")
            @RequestParam(defaultValue = "0")
            @DecimalMin(value = "-0.1") @DecimalMax(value = "1.0") BigDecimal riskFreeRate) {

        PerformanceResult result = service.evaluate(portfolioId, from, to, riskFreeRate);

        return ResponseEntity.ok()
                // Cached figures must not be cached again downstream, and a
                // live figure is only good until the next trade is booked.
                .header("Cache-Control", result.stale() ? "no-store" : "max-age=60")
                .body(PerformanceResponse.from(portfolioId, result));
    }
}
