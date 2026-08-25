package com.ledgerlens.transaction.web;

import com.ledgerlens.transaction.domain.Holding;
import com.ledgerlens.transaction.service.TransactionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * A portfolio's positions, derived from the ledger on every request.
 *
 * <p>Read-only by construction: there is no way to write a holding, because a
 * holding is not a fact — it is the sum of facts. The only way to change one is
 * to record a transaction.
 *
 * <p>analytics-service does not use this endpoint. It needs positions on every
 * day of a period, not just one, so it rebuilds the series from the transaction
 * history itself. This exists because "what do I hold right now" is the
 * question a client asks most, and answering it from the system of record is
 * cheaper than making every caller reimplement the fold.
 */
@RestController
@RequestMapping("/api/v1/portfolios/{portfolioId}/holdings")
public class HoldingController {

    private final TransactionService service;

    public HoldingController(TransactionService service) {
        this.service = service;
    }

    @GetMapping
    public List<HoldingResponse> holdings(
            @PathVariable UUID portfolioId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf) {

        return service.findHoldings(portfolioId, asOf).stream().map(HoldingResponse::from).toList();
    }

    public record HoldingResponse(String symbol, BigDecimal quantity) {

        static HoldingResponse from(Holding h) {
            return new HoldingResponse(h.symbol(), h.quantity());
        }
    }
}
