package com.ledgerlens.transaction.web;

import com.ledgerlens.transaction.domain.DailyPrice;
import com.ledgerlens.transaction.domain.DailyPriceBatchWriter;
import com.ledgerlens.transaction.service.PriceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Closing prices — the other half of what analytics-service needs.
 *
 * <p>Note the verb: {@code PUT}, not {@code POST}. Loading the same day of
 * prices twice must leave the system in the same state, because price loads get
 * retried, replayed and run twice by a nervous operator. Transactions use POST
 * precisely because they are <em>not</em> idempotent — posting the same trade
 * twice means it happened twice.
 */
@RestController
@RequestMapping(value = "/api/v1/prices", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@Tag(name = "Prices")
public class PriceController {

    private static final int MAX_BATCH = 5_000;

    private final PriceService service;

    public PriceController(PriceService service) {
        this.service = service;
    }

    @Operation(
            summary = "Load or restate closing prices",
            description = """
                    `PUT`, not `POST`, and the verb is the contract: this is idempotent. Loading the
                    same trading day twice leaves one row per (symbol, date), and a restated price
                    overwrites the previous one. Price loads get retried, replayed and run twice by
                    a nervous operator, so running one twice must leave the same state.

                    Contrast `POST /api/v1/transactions`, which is deliberately not idempotent —
                    posting the same trade twice means it happened twice.

                    Up to 5,000 prices per call, written in a single batched statement.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "`{\"written\": n}` — inserts and updates counted together"),
            @ApiResponse(responseCode = "400", description = "An empty list, a batch over the cap, or a malformed price",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    })
    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Integer> upsert(@Valid @RequestBody @NotEmpty @Size(max = MAX_BATCH) List<@Valid PriceUpsertRequest> prices) {
        List<DailyPriceBatchWriter.PriceRow> rows = prices.stream()
                .map(p -> new DailyPriceBatchWriter.PriceRow(p.symbol(), p.priceDate(), p.closePrice(), p.currency()))
                .toList();
        return Map.of("written", service.upsertAll(rows));
    }

    /**
     * @param symbols comma-separated, e.g. {@code ?symbols=IWDA,VWCE}
     * @param from    inclusive ISO date
     * @param to      inclusive ISO date
     */
    @Operation(summary = "Closing prices for several symbols over a date range",
            description = "Both bounds are inclusive. Days with no price are simply absent — "
                    + "the market calendar comes from the data rather than from a hard-coded holiday list.")
    @GetMapping
    public List<PriceResponse> find(
            @Parameter(description = "Comma-separated tickers", example = "IWDA,VWCE", required = true)
            @RequestParam @NotEmpty List<String> symbols,
            @Parameter(description = "Inclusive, ISO date", example = "2026-01-01", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @Parameter(description = "Inclusive, ISO date", example = "2026-08-25", required = true)
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return service.find(symbols, from, to).stream().map(PriceResponse::from).toList();
    }

    public record PriceUpsertRequest(
            @Schema(description = "Ticker; normalised to upper case", example = "IWDA")
            @NotNull @Pattern(regexp = "^[A-Za-z0-9.\\-]{1,16}$") String symbol,
            @Schema(description = "The trading day this close belongs to. A date, not an instant — "
                    + "a timestamp would let a timezone conversion move a price to the wrong day.",
                    example = "2026-08-25")
            @NotNull LocalDate priceDate,
            @Schema(description = "Closing price, up to 8 decimal places", example = "98.75000000")
            @NotNull @PositiveOrZero BigDecimal closePrice,
            @Schema(description = "ISO 4217 code", example = "EUR")
            @NotNull @Pattern(regexp = "^[A-Za-z]{3}$") String currency
    ) {
    }

    public record PriceResponse(String symbol, LocalDate priceDate, BigDecimal closePrice, String currency) {

        static PriceResponse from(DailyPrice p) {
            return new PriceResponse(p.getSymbol(), p.getPriceDate(), p.getClosePrice(), p.getCurrency());
        }
    }
}
