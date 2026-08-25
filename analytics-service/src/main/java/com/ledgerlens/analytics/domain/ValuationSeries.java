package com.ledgerlens.analytics.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Rebuilds a portfolio's daily net asset value from the raw ledger and a price
 * history.
 *
 * <p>This is the step that makes analytics-service worth having as a separate
 * service: it takes facts it does not own — transactions and prices — and
 * derives something neither of them contains. Nothing here is stored.
 *
 * <p>The valuation calendar is taken from the price data rather than from a
 * hard-coded holiday list. If the market was closed there is no price, so there
 * is no valuation point, which is the correct behaviour and costs no
 * maintenance.
 */
public final class ValuationSeries {

    private static final int CASH_SCALE = 4;

    private ValuationSeries() {
    }

    /**
     * @param transactions the whole ledger, any order; entries before {@code from} still
     *                     count, because they set the opening position
     * @param prices       closing prices; gaps are filled forward from the last known close
     * @param from         first valuation date, inclusive
     * @param to           last valuation date, inclusive
     */
    public static List<Valuation> build(List<TransactionRecord> transactions,
                                        List<PriceRecord> prices,
                                        LocalDate from,
                                        LocalDate to) {

        Map<String, NavigableMap<LocalDate, BigDecimal>> priceHistory = indexPrices(prices);
        List<LocalDate> tradingDays = tradingDays(prices, from, to);
        if (tradingDays.isEmpty()) {
            return List.of();
        }

        List<TransactionRecord> ordered = new ArrayList<>(transactions);
        ordered.sort(Comparator.comparing(TransactionRecord::executedAt));

        BigDecimal cash = BigDecimal.ZERO;
        Map<String, BigDecimal> positions = new HashMap<>();
        List<Valuation> series = new ArrayList<>(tradingDays.size());
        int next = 0;

        // Warm-up: everything that happened before the window sets the opening
        // cash and positions, but is not a cash flow *of* the window. Folding
        // it into the first day's flow instead would misattribute months of
        // contributions to a single day.
        LocalDate firstDay = tradingDays.getFirst();
        while (next < ordered.size() && dateOf(ordered.get(next)).isBefore(firstDay)) {
            TransactionRecord transaction = ordered.get(next++);
            cash = cash.add(transaction.cashAmount());
            applyToPositions(positions, transaction);
        }

        for (LocalDate day : tradingDays) {
            BigDecimal externalFlow = BigDecimal.ZERO;

            // Apply every transaction that had happened by the close of this
            // day. The cursor never rewinds, so the whole series is one pass
            // over the ledger rather than one pass per day. A trade dated on a
            // day the market was shut lands on the next valuation point, which
            // is the first moment its effect could be observed.
            while (next < ordered.size() && !dateOf(ordered.get(next)).isAfter(day)) {
                TransactionRecord transaction = ordered.get(next++);
                cash = cash.add(transaction.cashAmount());
                applyToPositions(positions, transaction);
                if (transaction.type().isExternalCashFlow()) {
                    externalFlow = externalFlow.add(transaction.cashAmount());
                }
            }

            series.add(new Valuation(day, netAssetValue(cash, positions, priceHistory, day),
                    externalFlow.setScale(CASH_SCALE, java.math.RoundingMode.HALF_EVEN)));
        }
        return series;
    }

    private static void applyToPositions(Map<String, BigDecimal> positions, TransactionRecord transaction) {
        switch (transaction.type().positionEffect()) {
            case INCREASE -> positions.merge(transaction.symbol(), transaction.quantity(), BigDecimal::add);
            case DECREASE -> positions.merge(transaction.symbol(), transaction.quantity().negate(), BigDecimal::add);
            case NONE -> {
                // Dividends and cash movements have already been applied to cash.
            }
        }
    }

    private static BigDecimal netAssetValue(BigDecimal cash,
                                            Map<String, BigDecimal> positions,
                                            Map<String, NavigableMap<LocalDate, BigDecimal>> priceHistory,
                                            LocalDate day) {
        BigDecimal value = cash;
        for (Map.Entry<String, BigDecimal> position : positions.entrySet()) {
            if (position.getValue().signum() == 0) {
                continue;
            }
            value = value.add(position.getValue().multiply(priceOn(priceHistory, position.getKey(), day)));
        }
        return value.setScale(CASH_SCALE, java.math.RoundingMode.HALF_EVEN);
    }

    /**
     * The last close at or before {@code day} — a forward fill, never a look
     * ahead. Using tomorrow's price to value today would be lookahead bias, and
     * it makes backtested performance look better than anything achievable.
     */
    private static BigDecimal priceOn(Map<String, NavigableMap<LocalDate, BigDecimal>> priceHistory,
                                      String symbol, LocalDate day) {
        NavigableMap<LocalDate, BigDecimal> history = priceHistory.get(symbol);
        if (history == null) {
            throw new MissingPriceException(symbol, day);
        }
        Map.Entry<LocalDate, BigDecimal> close = history.floorEntry(day);
        if (close == null) {
            throw new MissingPriceException(symbol, day);
        }
        return close.getValue();
    }

    private static Map<String, NavigableMap<LocalDate, BigDecimal>> indexPrices(List<PriceRecord> prices) {
        Map<String, NavigableMap<LocalDate, BigDecimal>> bySymbol = new HashMap<>();
        for (PriceRecord price : prices) {
            bySymbol.computeIfAbsent(price.symbol(), key -> new TreeMap<>())
                    .put(price.priceDate(), price.closePrice());
        }
        return bySymbol;
    }

    private static List<LocalDate> tradingDays(List<PriceRecord> prices, LocalDate from, LocalDate to) {
        return prices.stream()
                .map(PriceRecord::priceDate)
                .filter(date -> !date.isBefore(from) && !date.isAfter(to))
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * A trade is assigned to the UTC calendar day it executed on. Everything in
     * this service uses one timezone, because a portfolio whose day boundary
     * moves with the reader is a portfolio whose returns cannot be reproduced.
     */
    private static LocalDate dateOf(TransactionRecord transaction) {
        return transaction.executedAt().atZone(ZoneOffset.UTC).toLocalDate();
    }
}
