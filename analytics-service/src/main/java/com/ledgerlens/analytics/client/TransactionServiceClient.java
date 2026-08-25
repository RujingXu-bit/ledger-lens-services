package com.ledgerlens.analytics.client;

import com.ledgerlens.analytics.domain.PriceRecord;
import com.ledgerlens.analytics.domain.TransactionKind;
import com.ledgerlens.analytics.domain.TransactionRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

    /** transaction-service caps a page at 500; ask for the cap rather than guessing. */
    private static final int PAGE_SIZE = 500;

    private final RestClient restClient;
    private final int maxAttempts;

    public TransactionServiceClient(RestClient transactionServiceRestClient,
                                    TransactionServiceProperties properties) {
        this.restClient = transactionServiceRestClient;
        this.maxAttempts = properties.maxAttempts();
    }

    /** The whole ledger for a portfolio, oldest first. */
    public List<TransactionRecord> fetchTransactions(java.util.UUID portfolioId) {
        List<TransactionDto> page = withRetry("transactions", () -> restClient.get()
                .uri(builder -> builder.path("/api/v1/transactions")
                        .queryParam("portfolioId", portfolioId)
                        .queryParam("size", PAGE_SIZE)
                        .build())
                .retrieve()
                .onStatus(HttpStatusCode::is5xxServerError, (request, response) -> {
                    throw new UpstreamUnavailableException(
                            "transaction-service returned " + response.getStatusCode());
                })
                .body(TRANSACTION_LIST));

        return page == null ? List.of() : page.stream().map(TransactionDto::toDomain).toList();
    }

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
     */
    private <T> T withRetry(String what, java.util.function.Supplier<T> call) {
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
     * only what is read.
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
