package com.ledgerlens.transaction.web;

import com.ledgerlens.transaction.domain.Holding;
import com.ledgerlens.transaction.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
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
@RequestMapping(value = "/api/v1/portfolios/{portfolioId}/holdings", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Holdings")
public class HoldingController {

    private final TransactionService service;

    public HoldingController(TransactionService service) {
        this.service = service;
    }

    @Operation(
            summary = "Positions, derived from the ledger",
            description = """
                    There is no holdings table. A position is the net of buys and sells, summed
                    from the transaction log on every request, so it cannot disagree with the
                    ledger it comes from. Fully closed positions are omitted — holding zero of
                    something is not holding it.

                    Read-only by construction: the only way to change a holding is to record a
                    transaction.
                    """)
    @GetMapping
    public List<HoldingResponse> holdings(
            @PathVariable UUID portfolioId,
            @Parameter(description = "Positions as at this instant. Defaults to now. "
                    + "Any past value works, because the ledger is append-only.",
                    example = "2026-06-30T23:59:59Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant asOf) {

        return service.findHoldings(portfolioId, asOf).stream().map(HoldingResponse::from).toList();
    }

    public record HoldingResponse(
            @Schema(description = "Ticker, upper case", example = "IWDA") String symbol,
            @Schema(description = "Units held; fractional shares are supported", example = "383.04463100")
            BigDecimal quantity) {

        static HoldingResponse from(Holding h) {
            return new HoldingResponse(h.symbol(), h.quantity());
        }
    }
}
