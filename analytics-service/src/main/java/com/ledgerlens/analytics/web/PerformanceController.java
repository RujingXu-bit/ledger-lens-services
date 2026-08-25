package com.ledgerlens.analytics.web;

import com.ledgerlens.analytics.service.PerformanceResult;
import com.ledgerlens.analytics.service.PerformanceService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/portfolios/{portfolioId}/performance")
@Validated
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
    @GetMapping
    public ResponseEntity<PerformanceResponse> performance(
            @PathVariable UUID portfolioId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
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
