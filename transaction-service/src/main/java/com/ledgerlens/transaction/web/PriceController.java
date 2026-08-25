package com.ledgerlens.transaction.web;

import com.ledgerlens.transaction.domain.DailyPrice;
import com.ledgerlens.transaction.domain.DailyPriceBatchWriter;
import com.ledgerlens.transaction.service.PriceService;
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
@RequestMapping("/api/v1/prices")
@Validated
public class PriceController {

    private static final int MAX_BATCH = 5_000;

    private final PriceService service;

    public PriceController(PriceService service) {
        this.service = service;
    }

    @PutMapping
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
    @GetMapping
    public List<PriceResponse> find(
            @RequestParam @NotEmpty List<String> symbols,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return service.find(symbols, from, to).stream().map(PriceResponse::from).toList();
    }

    public record PriceUpsertRequest(
            @NotNull @Pattern(regexp = "^[A-Za-z0-9.\\-]{1,16}$") String symbol,
            @NotNull LocalDate priceDate,
            @NotNull @PositiveOrZero BigDecimal closePrice,
            @NotNull @Pattern(regexp = "^[A-Za-z]{3}$") String currency
    ) {
    }

    public record PriceResponse(String symbol, LocalDate priceDate, BigDecimal closePrice, String currency) {

        static PriceResponse from(DailyPrice p) {
            return new PriceResponse(p.getSymbol(), p.getPriceDate(), p.getClosePrice(), p.getCurrency());
        }
    }
}
