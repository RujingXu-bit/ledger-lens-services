package com.ledgerlens.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Rebuilding the daily net asset value is where a wrong number would be hardest
 * to spot downstream, so it gets tested on its own rather than only through the
 * metrics.
 */
class ValuationSeriesTest {

    private static final LocalDate MON = LocalDate.of(2026, 1, 5);
    private static final LocalDate TUE = MON.plusDays(1);
    private static final LocalDate WED = MON.plusDays(2);

    @Test
    void netAssetValueIsCashPlusPositionsMarkedAtTheClose() {
        List<TransactionRecord> ledger = List.of(
                deposit(MON, "1000"),
                buy(MON, "ACME", "10", "-1000"));
        List<PriceRecord> prices = List.of(
                price("ACME", MON, "100"), price("ACME", TUE, "110"), price("ACME", WED, "99"));

        List<Valuation> series = ValuationSeries.build(ledger, prices, MON, WED);

        assertThat(series).extracting(Valuation::netAssetValue)
                .containsExactly(new BigDecimal("1000.0000"), new BigDecimal("1100.0000"), new BigDecimal("990.0000"));
    }

    @Test
    void onlyDepositsAndWithdrawalsCountAsExternalCashFlow() {
        List<TransactionRecord> ledger = List.of(
                deposit(MON, "1000"),
                buy(MON, "ACME", "10", "-1000"),
                // A dividend is income, so it must not be netted out of the return.
                new TransactionRecord(TransactionKind.DIVIDEND, "ACME", null, new BigDecimal("25"), at(TUE)),
                new TransactionRecord(TransactionKind.WITHDRAWAL, null, null, new BigDecimal("-200"), at(WED)));
        List<PriceRecord> prices = List.of(
                price("ACME", MON, "100"), price("ACME", TUE, "100"), price("ACME", WED, "100"));

        List<Valuation> series = ValuationSeries.build(ledger, prices, MON, WED);

        assertThat(series).extracting(Valuation::externalCashFlow)
                .containsExactly(new BigDecimal("1000.0000"), new BigDecimal("0.0000"), new BigDecimal("-200.0000"));
        // The dividend still raised net asset value: 1,000 of stock plus 25 cash.
        assertThat(series.get(1).netAssetValue()).isEqualByComparingTo("1025.0000");
    }

    @Test
    void sellingReducesThePositionAndReturnsCashToTheBalance() {
        List<TransactionRecord> ledger = List.of(
                deposit(MON, "1000"),
                buy(MON, "ACME", "10", "-1000"),
                new TransactionRecord(TransactionKind.SELL, "ACME", new BigDecimal("4"), new BigDecimal("440"), at(TUE)));
        List<PriceRecord> prices = List.of(
                price("ACME", MON, "100"), price("ACME", TUE, "110"));

        List<Valuation> series = ValuationSeries.build(ledger, prices, MON, TUE);

        // 6 shares at 110, plus 440 of cash.
        assertThat(series.getLast().netAssetValue()).isEqualByComparingTo("1100.0000");
    }

    /**
     * A stale price is carried forward, never interpolated and never taken from
     * the future. Valuing today with tomorrow's close is lookahead bias, and it
     * makes any backtest look better than reality allowed.
     */
    @Test
    void aMissingCloseIsFilledForwardFromTheLastKnownPrice() {
        List<TransactionRecord> ledger = List.of(
                deposit(MON, "2000"),
                buy(MON, "ACME", "10", "-1000"),
                buy(MON, "STALE", "10", "-1000"));
        List<PriceRecord> prices = List.of(
                price("ACME", MON, "100"), price("ACME", TUE, "120"),
                // STALE stopped printing after Monday.
                price("STALE", MON, "100"));

        List<Valuation> series = ValuationSeries.build(ledger, prices, MON, TUE);

        // Tuesday: ACME 10 x 120 = 1,200, STALE still marked at Monday's 100.
        assertThat(series.getLast().netAssetValue()).isEqualByComparingTo("2200.0000");
    }

    @Test
    void aPositionWithNoPriceAtAllFailsLoudlyInsteadOfBeingValuedAtZero() {
        List<TransactionRecord> ledger = List.of(
                deposit(MON, "1000"),
                buy(MON, "GHOST", "10", "-1000"));
        List<PriceRecord> prices = List.of(
                price("ACME", MON, "100"),
                // GHOST only starts printing on Wednesday, after it was bought.
                price("GHOST", WED, "100"));

        assertThatThrownBy(() -> ValuationSeries.build(ledger, prices, MON, WED))
                .isInstanceOf(MissingPriceException.class)
                .hasMessageContaining("GHOST");
    }

    @Test
    void transactionsBeforeTheWindowStillSetTheOpeningPosition() {
        List<TransactionRecord> ledger = List.of(
                deposit(MON.minusMonths(6), "1000"),
                buy(MON.minusMonths(6), "ACME", "10", "-1000"));
        List<PriceRecord> prices = List.of(price("ACME", TUE, "150"), price("ACME", WED, "150"));

        List<Valuation> series = ValuationSeries.build(ledger, prices, TUE, WED);

        assertThat(series).hasSize(2);
        assertThat(series.getFirst().netAssetValue()).isEqualByComparingTo("1500.0000");
        // The deposit happened long ago; it is not a flow inside this window.
        assertThat(series.getFirst().externalCashFlow()).isEqualByComparingTo("0.0000");
    }

    @Test
    void theValuationCalendarComesFromThePriceDataNotAHardCodedHolidayList() {
        List<TransactionRecord> ledger = List.of(deposit(MON, "1000"), buy(MON, "ACME", "10", "-1000"));
        // No price on Tuesday at all: the market was shut, so there is no point.
        List<PriceRecord> prices = List.of(price("ACME", MON, "100"), price("ACME", WED, "100"));

        List<Valuation> series = ValuationSeries.build(ledger, prices, MON, WED);

        assertThat(series).extracting(Valuation::date).containsExactly(MON, WED);
    }

    /**
     * The property the whole design exists to protect, end to end: doubling the
     * money in a portfolio by paying more in must not read as a 100% return.
     */
    @Test
    void aMidPeriodContributionDoesNotInflateTheReportedReturn() {
        List<TransactionRecord> ledger = List.of(
                deposit(MON, "1000"),
                buy(MON, "ACME", "10", "-1000"),
                deposit(WED, "1100"),
                buy(WED, "ACME", "10", "-1100"));
        List<PriceRecord> prices = List.of(
                price("ACME", MON, "100"), price("ACME", TUE, "110"), price("ACME", WED, "110"));

        List<Valuation> series = ValuationSeries.build(ledger, prices, MON, WED);
        PerformanceMetrics metrics = PerformanceCalculator.evaluate(series, BigDecimal.ZERO);

        // Net asset value went 1,000 -> 1,100 -> 2,200, but only the first step
        // was performance. Unadjusted this would report +120%.
        assertThat(series).extracting(Valuation::netAssetValue)
                .containsExactly(new BigDecimal("1000.0000"), new BigDecimal("1100.0000"), new BigDecimal("2200.0000"));
        assertThat(metrics.totalReturn()).isEqualByComparingTo("0.100000");
    }

    private static TransactionRecord deposit(LocalDate day, String amount) {
        return new TransactionRecord(TransactionKind.DEPOSIT, null, null, new BigDecimal(amount), at(day));
    }

    private static TransactionRecord buy(LocalDate day, String symbol, String quantity, String cashAmount) {
        return new TransactionRecord(
                TransactionKind.BUY, symbol, new BigDecimal(quantity), new BigDecimal(cashAmount), at(day));
    }

    private static PriceRecord price(String symbol, LocalDate day, String close) {
        return new PriceRecord(symbol, day, new BigDecimal(close));
    }

    private static Instant at(LocalDate day) {
        return day.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().plusSeconds(9 * 3600);
    }
}
