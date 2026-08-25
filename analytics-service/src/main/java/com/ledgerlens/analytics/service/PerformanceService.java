package com.ledgerlens.analytics.service;

import com.ledgerlens.analytics.client.TransactionServiceClient;
import com.ledgerlens.analytics.client.TransactionServiceProperties;
import com.ledgerlens.analytics.client.UpstreamUnavailableException;
import com.ledgerlens.analytics.domain.PerformanceCalculator;
import com.ledgerlens.analytics.domain.PerformanceMetrics;
import com.ledgerlens.analytics.domain.PriceRecord;
import com.ledgerlens.analytics.domain.TransactionRecord;
import com.ledgerlens.analytics.domain.Valuation;
import com.ledgerlens.analytics.domain.ValuationSeries;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Fetch, derive, evaluate — and decide what to do when the fetch fails.
 */
@Service
public class PerformanceService {

    private static final Logger log = LoggerFactory.getLogger(PerformanceService.class);

    /**
     * How far before the window to ask for prices. The first valuation day needs
     * a close at or before it, and the day the window opens may be a holiday, a
     * weekend, or a day a thinly traded fund did not print.
     */
    private static final int PRICE_LOOKBACK_DAYS = 30;

    private final TransactionServiceClient client;
    private final MetricsCache cache;
    private final TransactionServiceProperties properties;
    private final Clock clock;

    public PerformanceService(TransactionServiceClient client,
                              MetricsCache cache,
                              TransactionServiceProperties properties,
                              Clock clock) {
        this.client = client;
        this.cache = cache;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @param from null means "from the first transaction"
     * @param to   null means "up to today"
     */
    public PerformanceResult evaluate(UUID portfolioId, LocalDate from, LocalDate to, BigDecimal riskFreeRate) {
        LocalDate windowEnd = to == null ? LocalDate.now(clock) : to;
        MetricsCache.Key key = new MetricsCache.Key(portfolioId, from, windowEnd, riskFreeRate);

        try {
            return compute(portfolioId, from, windowEnd, riskFreeRate, key);
        } catch (UpstreamUnavailableException e) {
            // The fallback. Whether stale data beats no data is a product
            // decision, not a technical one: for a performance dashboard a
            // clearly-labelled figure from ten minutes ago is more useful than
            // an error page. For anything that moves money it would not be, and
            // the answer there is to fail. The staleness is always reported.
            Optional<PerformanceResult> stale = cache.find(key, properties.staleTolerance());
            if (stale.isPresent()) {
                log.warn("serving stale metrics for portfolio {} computed at {}: {}",
                        portfolioId, stale.get().computedAt(), e.getMessage());
                return stale.get();
            }
            log.error("transaction-service unavailable and no usable cached result for portfolio {}", portfolioId);
            throw e;
        }
    }

    private PerformanceResult compute(UUID portfolioId,
                                      LocalDate from,
                                      LocalDate windowEnd,
                                      BigDecimal riskFreeRate,
                                      MetricsCache.Key key) {

        List<TransactionRecord> transactions = client.fetchTransactions(portfolioId);
        if (transactions.isEmpty()) {
            // A definite answer, so it is not cached and not retried.
            throw new PortfolioNotFoundException(portfolioId);
        }

        LocalDate windowStart = from == null ? firstTransactionDate(transactions) : from;

        List<String> symbols = transactions.stream()
                .map(TransactionRecord::symbol)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();

        List<PriceRecord> prices = client.fetchPrices(
                symbols, windowStart.minusDays(PRICE_LOOKBACK_DAYS), windowEnd);

        List<Valuation> series = ValuationSeries.build(transactions, prices, windowStart, windowEnd);
        PerformanceMetrics metrics = PerformanceCalculator.evaluate(series, riskFreeRate);

        PerformanceResult result = new PerformanceResult(metrics, Instant.now(clock), false);
        cache.put(key, result);
        return result;
    }

    private static LocalDate firstTransactionDate(List<TransactionRecord> transactions) {
        return transactions.stream()
                .map(TransactionRecord::executedAt)
                .min(Comparator.naturalOrder())
                .orElseThrow()
                .atZone(ZoneOffset.UTC)
                .toLocalDate();
    }
}
