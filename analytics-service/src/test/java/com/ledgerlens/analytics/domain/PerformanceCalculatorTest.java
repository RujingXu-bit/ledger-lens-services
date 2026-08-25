package com.ledgerlens.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Every expected number below is worked out by hand in the comment beside it.
 * A test whose expectation is computed by re-running the implementation proves
 * only that the code is consistent with itself.
 */
class PerformanceCalculatorTest {

    private static final LocalDate DAY_1 = LocalDate.of(2026, 1, 5);
    private static final BigDecimal NO_RISK_FREE_RATE = BigDecimal.ZERO;

    @Nested
    @DisplayName("cash flows are not returns")
    class CashFlowNeutrality {

        /**
         * The single most important property in this class. Without the cash
         * flow adjustment, saving money would look identical to earning it.
         */
        @Test
        void aDepositDoesNotCreateAReturn() {
            // 1,000 -> 2,000, of which 1,000 walked in the door.
            List<Valuation> series = List.of(
                    valuation(0, "1000", "0"),
                    valuation(1, "2000", "1000"));

            assertThat(PerformanceCalculator.dailyReturns(series)).containsExactly(0.0);
        }

        @Test
        void aWithdrawalIsNotALoss() {
            List<Valuation> series = List.of(
                    valuation(0, "2000", "0"),
                    valuation(1, "1000", "-1000"));

            assertThat(PerformanceCalculator.dailyReturns(series)).containsExactly(0.0);
        }

        /**
         * A dividend arrives as cash but is carried as an internal flow, so it
         * raises net asset value and is counted as return - which is what it is.
         */
        @Test
        void incomeCountsAsReturnBecauseItIsNotAnExternalFlow() {
            List<Valuation> series = List.of(
                    valuation(0, "1000", "0"),
                    valuation(1, "1020", "0"));

            assertThat(PerformanceCalculator.dailyReturns(series)).containsExactly(0.02);
        }

        @Test
        void contributingIntoAFallingMarketStillShowsTheLoss() {
            // 10,000 falls 10% to 9,000, and 1,000 is paid in the same day: 10,000.
            // Naively that is flat; correctly it is -10%.
            List<Valuation> series = List.of(
                    valuation(0, "10000", "0"),
                    valuation(1, "10000", "1000"));

            assertThat(PerformanceCalculator.dailyReturns(series)[0]).isCloseTo(-0.10, within(1e-12));
        }

        @Test
        void anEmptyPortfolioHasNoReturnOnTheDayMoneyFirstArrives() {
            List<Valuation> series = List.of(
                    valuation(0, "0", "0"),
                    valuation(1, "5000", "5000"));

            assertThat(PerformanceCalculator.dailyReturns(series)).containsExactly(0.0);
        }
    }

    @Nested
    @DisplayName("the four figures")
    class Metrics {

        @Test
        void totalReturnChainLinksRatherThanAdding() {
            // +10% then -10% is -1%, not 0%.
            PerformanceMetrics metrics = evaluate(
                    valuation(0, "1000", "0"),
                    valuation(1, "1100", "0"),
                    valuation(2, "990", "0"));

            assertThat(metrics.totalReturn()).isEqualByComparingTo("-0.010000");
            assertThat(metrics.observations()).isEqualTo(2);
        }

        @Test
        void maxDrawdownIsTheWorstPeakToTroughFall() {
            // Index: 1.10, then 0.88 (-20%), then 0.924.
            // Peak 1.10, trough 0.88 -> 0.88/1.10 - 1 = -0.20.
            double worst = PerformanceCalculator.maxDrawdown(new double[]{0.10, -0.20, 0.05});

            assertThat(worst).isCloseTo(-0.20, within(1e-12));
        }

        /**
         * The reason drawdown is measured on the return index and never on net
         * asset value: this portfolio lost nothing, it simply paid its owner.
         */
        @Test
        void payingAnIncomeIsNotADrawdown() {
            PerformanceMetrics metrics = evaluate(
                    valuation(0, "10000", "0"),
                    valuation(1, "9000", "-1000"),
                    valuation(2, "8000", "-1000"));

            assertThat(metrics.maxDrawdown()).isEqualByComparingTo("0.000000");
            assertThat(metrics.totalReturn()).isEqualByComparingTo("0.000000");
        }

        @Test
        void volatilityUsesTheSampleDeviationAndAnnualisesBySqrt252() {
            // returns [+1%, -1%]: mean 0, sum of squares 0.0002, /(n-1) = 0.0002,
            // sd = 0.01414214, x sqrt(252) = 0.22449...
            double volatility = PerformanceCalculator.annualisedVolatility(new double[]{0.01, -0.01});

            assertThat(volatility).isCloseTo(0.2244994, within(1e-6));
        }

        @Test
        void annualisationIsGeometricNotArithmetic() {
            // Exactly one year of observations: the annual figure is the total.
            assertThat(PerformanceCalculator.annualise(0.10, 252)).isCloseTo(0.10, within(1e-12));

            // Two years at +21% total: sqrt(1.21) - 1 = 10%, not 10.5%.
            assertThat(PerformanceCalculator.annualise(0.21, 504)).isCloseTo(0.10, within(1e-12));
        }

        @Test
        void aTotalLossAnnualisesToMinusOneHundredPercentRatherThanNaN() {
            assertThat(PerformanceCalculator.annualise(-1.0, 252)).isEqualTo(-1.0);
        }
    }

    @Nested
    @DisplayName("Sharpe ratio")
    class Sharpe {

        @Test
        void isNullWhenThereIsNoVolatilityRatherThanInfinite() {
            PerformanceMetrics metrics = evaluate(
                    valuation(0, "1000", "0"),
                    valuation(1, "1100", "0"),
                    valuation(2, "1210", "0"));

            assertThat(metrics.annualisedVolatility()).isEqualByComparingTo("0.000000");
            assertThat(metrics.sharpeRatio()).isNull();
        }

        @Test
        void risesWhenTheRiskFreeRateFallsAndTheRateIsEchoedBack() {
            List<Valuation> series = List.of(
                    valuation(0, "1000", "0"),
                    valuation(1, "1010", "0"),
                    valuation(2, "1005", "0"),
                    valuation(3, "1020", "0"));

            PerformanceMetrics atZero = PerformanceCalculator.evaluate(series, BigDecimal.ZERO);
            PerformanceMetrics atThreePercent = PerformanceCalculator.evaluate(series, new BigDecimal("0.03"));

            assertThat(atZero.sharpeRatio()).isGreaterThan(atThreePercent.sharpeRatio());
            assertThat(atThreePercent.riskFreeRate()).isEqualByComparingTo("0.03");
        }
    }

    @Test
    void refusesToInventStatisticsFromASinglePoint() {
        assertThatThrownBy(() -> PerformanceCalculator.evaluate(List.of(valuation(0, "1000", "0")), NO_RISK_FREE_RATE))
                .isInstanceOf(InsufficientDataException.class)
                .hasMessageContaining("two valuation points");
    }

    private static PerformanceMetrics evaluate(Valuation... series) {
        return PerformanceCalculator.evaluate(List.of(series), NO_RISK_FREE_RATE);
    }

    private static Valuation valuation(int dayOffset, String netAssetValue, String externalCashFlow) {
        return new Valuation(DAY_1.plusDays(dayOffset), new BigDecimal(netAssetValue), new BigDecimal(externalCashFlow));
    }
}
