package com.ledgerlens.transaction.web;

import com.ledgerlens.transaction.domain.Transaction;
import com.ledgerlens.transaction.service.TransactionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The ledger's HTTP surface.
 *
 * <p>Versioned in the path from the first commit. Adding {@code /v1} later
 * means breaking every existing caller on the day you need the flexibility
 * most.
 *
 * <p>There is no PUT and no DELETE. The ledger is append-only, so an
 * unsupported method here is a design statement rather than an omission —
 * a mistaken entry is corrected with a reversing transaction.
 */
@RestController
@RequestMapping("/api/v1/transactions")
@Validated
public class TransactionController {

    /** A ceiling the caller cannot raise; an unbounded read is an outage waiting for a big portfolio. */
    private static final int MAX_PAGE_SIZE = 500;

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    /**
     * Records a transaction.
     *
     * <p>Returns 201 with a {@code Location} header rather than 200: the server
     * chose the identifier, so the response has to tell the client where the
     * new resource lives.
     */
    @PostMapping
    public ResponseEntity<TransactionResponse> create(@Valid @RequestBody CreateTransactionRequest request,
                                                      UriComponentsBuilder uriBuilder) {
        Transaction saved = service.record(
                request.portfolioId(),
                request.type(),
                request.symbol(),
                request.quantity(),
                request.pricePerUnit(),
                request.fee(),
                request.amount(),
                request.currency(),
                request.executedAt());

        URI location = uriBuilder.path("/api/v1/transactions/{id}").build(saved.getId());
        return ResponseEntity.created(location).body(TransactionResponse.from(saved));
    }

    @GetMapping("/{id}")
    public TransactionResponse getById(@PathVariable UUID id) {
        return TransactionResponse.from(service.findById(id));
    }

    /**
     * The portfolio ledger, oldest first — the read analytics-service depends on.
     *
     * <p>{@code portfolioId} is required, not optional-with-a-default: the
     * alternative is an endpoint that silently returns every portfolio in the
     * system when a caller forgets a parameter.
     *
     * <p>{@code from} and {@code to} are inclusive ISO-8601 instants, e.g.
     * {@code 2026-01-01T00:00:00Z}.
     */
    @GetMapping
    public List<TransactionResponse> list(
            @RequestParam UUID portfolioId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @jakarta.validation.constraints.PositiveOrZero int page,
            @RequestParam(defaultValue = "100") @Positive @Max(MAX_PAGE_SIZE) int size) {

        return service.findForPortfolio(portfolioId, from, to, PageRequest.of(page, size))
                .stream()
                .map(TransactionResponse::from)
                .toList();
    }
}
