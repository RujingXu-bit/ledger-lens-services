package com.ledgerlens.analytics.client;

import com.ledgerlens.analytics.domain.PriceRecord;
import com.ledgerlens.analytics.domain.TransactionKind;
import com.ledgerlens.analytics.domain.TransactionRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * The one place in this service that knows transaction-service exists.
 *
 * <p>Everything above this class works in domain types. That is what makes the
 * dependency replaceable: swapping REST for a message queue, or for a local
 * database, changes this file and nothing else.
 *
 * <p>Two things every synchronous inter-service call needs and tutorials leave
 * out — a timeout, and a decision about what to do when it fires. A call with no
 * timeout inherits the operating system's, which is minutes; under load that
 * means every thread in this service ends up parked on a dead upstream, and one
 * service's outage becomes two. The timeouts live in
 * {@link TransactionServiceProperties}.
 *
 * <p>Failures are sorted into three kinds, because the right response differs:
 * <ul>
 *   <li>no answer (timeout, refused, 5xx) → {@link UpstreamUnavailableException}, retryable, may fall back on cache</li>
 *   <li>a definite "your request is wrong" (4xx) → {@link UpstreamRejectedException}, never retried, never cached over</li>
 *   <li>more data than one request should carry → {@link LedgerTooLargeException}, a refusal rather than a partial answer</li>
 * </ul>
 */
@Component
public class TransactionServiceClient {

    private static final Logger log = LoggerFactory.getLogger(TransactionServiceClient.class);

    private static final ParameterizedTypeReference<List<TransactionDto>> TRANSACTION_LIST =
            new ParameterizedTypeReference<>() {
            };
    private static final ParameterizedTypeReference<List<PriceDto>> PRICE_LIST =
            new ParameterizedTypeReference<>() {
            };

    /**
     * A backstop, not a page count anybody should hit: at the default page size
     * this is 50,000 transactions for one portfolio.
     */
    private static final int MAX_PAGES = 100;

    private final RestClient restClient;
    private final int maxAttempts;
    private final int pageSize;

    public TransactionServiceClient(RestClient transactionServiceRestClient,
                                    TransactionServiceProperties properties) {
        this.restClient = transactionServiceRestClient;
        this.maxAttempts = properties.maxAttempts();
        this.pageSize = properties.pageSize();
    }

    /**
     * The whole ledger for a portfolio, oldest first, read page by page until it
     * is exhausted.
     *
     * <p>Asking for a single page and using whatever came back is the trap here.
     * transaction-service caps a page at 500, so a busier portfolio would have
     * been silently truncated and every figure derived from it would have been
     * wrong with nothing in the response to say so. Paging is safe because the
     * ledger is ordered by {@code (executedAt, id)}: the tiebreaker means the
     * boundary between two pages cannot skip or repeat a row.
     *
     * @throws LedgerTooLargeException rather than returning a partial ledger
     */
    public List<TransactionRecord> fetchTransactions(UUID portfolioId) {
        List<TransactionRecord> ledger = new ArrayList<>();

        for (int page = 0; page < MAX_PAGES; page++) {
            List<TransactionDto> batch = fetchTransactionPage(portfolioId, page);
            if (batch.isEmpty()) {
                return ledger;
            }
            batch.forEach(dto -> ledger.add(dto.toDomain()));

            // A short page is the last page. Only a full one justifies asking again.
            if (batch.size() < pageSize) {
                if (page > 0) {
                    log.debug("read {} transactions for portfolio {} across {} pages",
                            ledger.size(), portfolioId, page + 1);
                }
                return ledger;
            }
        }
        throw new LedgerTooLargeException(portfolioId, MAX_PAGES * pageSize);
    }

    private List<TransactionDto> fetchTransactionPage(UUID portfolioId, int page) {
        List<TransactionDto> batch = withRetry("transactions page " + page, () -> restClient.get()
                .uri(builder -> builder.path("/api/v1/transactions")
                        .queryParam("portfolioId", portfolioId)
                        .queryParam("page", page)
                        .queryParam("size", pageSize)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                    throw new UpstreamRejectedException(response.getStatusCode().value(), "transactions");
                })
                .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                    throw new UpstreamUnavailableException(
                            "transaction-service returned " + response.getStatusCode());
                })
                .body(TRANSACTION_LIST));
        return batch == null ? List.of() : batch;
    }

    /**
     * Closing prices for the symbols a portfolio holds, over the valuation
     * window. Not paged: the response is bounded by the symbol count times the
     * number of trading days, both of which this service already knows.
     */
    public List<PriceRecord> fetchPrices(List<String> symbols, LocalDate from, LocalDate to) {
        if (symbols.isEmpty()) {
            return List.of();
        }
        List<PriceDto> prices = withRetry("prices", () -> restClient.get()
                .uri(builder -> builder.path("/api/v1/prices")
                        .queryParam("symbols", String.join(",", symbols))
                        .queryParam("from", from)
                        .queryParam("to", to)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (request, response) -> {
                    throw new UpstreamRejectedException(response.getStatusCode().value(), "prices");
                })
                .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                    throw new UpstreamUnavailableException(
                            "transaction-service returned " + response.getStatusCode());
                })
                .body(PRICE_LIST));

        return prices == null ? List.of() : prices.stream().map(PriceDto::toDomain).toList();
    }

    /**
     * A deliberately small retry: both calls are GETs, so they are safe to
     * repeat, and a single immediate retry absorbs the common case of one
     * connection being dropped mid-rollout.
     *
     * <p>No exponential backoff, no circuit breaker, no Resilience4j. Retries
     * are load amplification — a struggling upstream getting every request
     * twice is how a slowdown becomes an outage — so the count stays at two and
     * the timeout stays short. That is the honest trade at this size; a
     * circuit breaker earns its keep when there is enough traffic for it to
     * observe, and it is not free to reason about.
     *
     * <p>{@link UpstreamRejectedException} is not caught here on purpose. A 4xx
     * is a deterministic answer: asking the same question again gets the same
     * refusal, so retrying would only double the noise.
     */
    private <T> T withRetry(String what, Supplier<T> call) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return call.get();
            } catch (ResourceAccessException e) {
                // Connection refused, connection reset, or a read timeout.
                last = new UpstreamUnavailableException(
                        "transaction-service did not respond while fetching " + what, e);
                log.warn("attempt {}/{} to fetch {} failed: {}", attempt, maxAttempts, what, e.getMessage());
            } catch (UpstreamUnavailableException e) {
                last = e;
                log.warn("attempt {}/{} to fetch {} failed: {}", attempt, maxAttempts, what, e.getMessage());
            }
        }
        throw last;
    }

    /**
     * The wire shape, declared here rather than shared with transaction-service.
     * Unknown fields are ignored by Jackson's default, so the upstream can add
     * fields without breaking this service — which is the point of declaring
     * only what is read. What this record contains is pinned by the contract in
     * {@code docs/contracts/analytics-service-expects.json}.
     */
    record TransactionDto(TransactionKind type,
                          String symbol,
                          BigDecimal quantity,
                          BigDecimal cashAmount,
                          Instant executedAt) {

        TransactionRecord toDomain() {
            return new TransactionRecord(type, symbol, quantity, cashAmount, executedAt);
        }
    }

    record PriceDto(String symbol, LocalDate priceDate, BigDecimal closePrice) {

        PriceRecord toDomain() {
            return new PriceRecord(symbol, priceDate, closePrice);
        }
    }
}
