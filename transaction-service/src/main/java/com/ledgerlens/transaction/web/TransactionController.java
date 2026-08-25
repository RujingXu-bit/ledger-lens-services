package com.ledgerlens.transaction.web;

import com.ledgerlens.transaction.domain.Transaction;
import com.ledgerlens.transaction.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
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
@RequestMapping(value = "/api/v1/transactions", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@Tag(name = "Transactions")
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
    @Operation(
            summary = "Record a transaction",
            description = """
                    Appends one entry to the ledger. Not idempotent: posting the same body twice
                    records two transactions, because the same trade happening twice is a thing
                    that can happen. Callers needing at-most-once should deduplicate upstream.

                    Which fields are required depends on `type`:

                    | type | requires | must omit |
                    |---|---|---|
                    | `BUY`, `SELL` | `symbol`, `quantity`, `pricePerUnit` | `amount` |
                    | `DIVIDEND` | `symbol`, `amount` | `quantity`, `pricePerUnit` |
                    | `DEPOSIT`, `WITHDRAWAL` | `amount` | `symbol`, `quantity`, `pricePerUnit` |

                    `amount` is always positive; direction is carried by `type`. The signed
                    `cashAmount` on the response is computed by the server and is not accepted
                    as input.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Recorded. `Location` points at the new resource."),
            @ApiResponse(responseCode = "400",
                    description = "The payload's shape is wrong — a missing field, a negative quantity. "
                            + "`errors` lists every violation at once.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail"))),
            @ApiResponse(responseCode = "422",
                    description = "Well-formed, but describes a transaction that cannot exist — "
                            + "a BUY with no symbol, a DEPOSIT carrying a quantity.",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    })
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
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

    @Operation(summary = "Fetch one transaction")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Found"),
            @ApiResponse(responseCode = "404", description = "No such transaction",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    })
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
    @Operation(
            summary = "A portfolio's ledger, oldest first",
            description = """
                    Ordered by `executedAt`, then by `id` — the tiebreaker matters, because without
                    it two transactions sharing a timestamp come back in whatever order the database
                    finds convenient, which makes paging skip and repeat rows.

                    This is the read analytics-service depends on.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The ledger, possibly empty"),
            @ApiResponse(responseCode = "400",
                    description = "`portfolioId` missing, or `size` above the cap of 500",
                    content = @Content(mediaType = "application/problem+json",
                            schema = @Schema(ref = "#/components/schemas/ProblemDetail")))
    })
    @GetMapping
    public List<TransactionResponse> list(
            @Parameter(description = "Required. There is deliberately no way to read every portfolio at once.",
                    required = true)
            @RequestParam UUID portfolioId,
            @Parameter(description = "Inclusive lower bound, ISO-8601 instant, e.g. 2026-01-01T00:00:00Z",
                    example = "2026-01-01T00:00:00Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @Parameter(description = "Inclusive upper bound, ISO-8601 instant",
                    example = "2026-12-31T23:59:59Z")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @jakarta.validation.constraints.PositiveOrZero int page,
            @RequestParam(defaultValue = "100") @Positive @Max(MAX_PAGE_SIZE) int size) {

        return service.findForPortfolio(portfolioId, from, to, PageRequest.of(page, size))
                .stream()
                .map(TransactionResponse::from)
                .toList();
    }
}
