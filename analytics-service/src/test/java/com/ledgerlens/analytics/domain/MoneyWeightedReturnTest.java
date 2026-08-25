package com.ledgerlens.analytics.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every case below has a closed-form answer worked out by hand, so the test is
 * checking the solver against algebra rather than against itself.
 */
class MoneyWeightedReturnTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 1);

    @Test
    @DisplayName("one payment in, one value out: the rate is the plain compound growth")
    void twoFlowsOverTwoYears() {
        // -1,000 at t=0, +1,210 at t=730 days.
        // 1000(1+r)^2 = 1210  ->  r = sqrt(1.21) - 1 = 10%.
        List<Valuation> series = List.of(
                valuation(0, "1000", "0"),
                valuation(730, "1210", "0"));

        assertThat(MoneyWeightedReturn.annualised(series)).isCloseTo(0.10, within(1e-6));
    }

    @Test
    @DisplayName("a contribution part way through is discounted from its own date")
    void threeFlowsWithAnIntermediateContribution() {
        // -1,000 at t=0, -1,000 at t=365, +2,310 at t=730.
        // Let x = 1+r:  1000x^2 + 1000x - 2310 = 0  ->  x = 1.1, so r = 10%.
        List<Valuation> series = List.of(
                valuation(0, "1000", "0"),
                valuation(365, "2000", "1000"),
                valuation(730, "2310", "0"));

        assertThat(MoneyWeightedReturn.annualised(series)).isCloseTo(0.10, within(1e-6));
    }

    /**
     * The reason both figures are reported. Same portfolio, same start and end
     * value per unit — but the investor put most of their money in near the
     * bottom, so what they earned is not what the portfolio returned.
     */
    @Test
    void divergesFromTheTimeWeightedReturnWhenContributionsAreWellTimed() {
        // The portfolio halves, then quadruples: time-weighted it doubles.
        // The investor holds 1,000 through the fall and adds 10,000 at the bottom.
        List<Valuation> series = List.of(
                valuation(0, "1000", "0"),
                valuation(182, "10500", "10000"),   // 1,000 -> 500, plus a 10,000 contribution
                valuation(365, "42000", "0"));      // x4

        double timeWeighted = PerformanceCalculator.chainLink(PerformanceCalculator.dailyReturns(series));
        Double moneyWeighted = MoneyWeightedReturn.annualised(series);

        assertThat(timeWeighted).isCloseTo(1.0, within(1e-9));      // +100%
        assertThat(moneyWeighted).isNotNull();
        // Buying the dip paid: the investor's own rate is far above the portfolio's.
        assertThat(moneyWeighted).isGreaterThan(timeWeighted);
    }

    @Test
    void returnsNullRatherThanInventingARateWhenTheFlowsDoNotDefineOne() {
        // Money only ever went in, and the portfolio is now worth nothing.
        // No rate balances that, so there is no answer to give.
        List<Valuation> series = List.of(
                valuation(0, "1000", "0"),
                valuation(365, "0", "0"));

        assertThat(MoneyWeightedReturn.annualised(series)).isNull();
    }

    @Test
    void aTotalLossIsNotSilentlyReportedAsZero() {
        Double rate = MoneyWeightedReturn.annualised(List.of(
                valuation(0, "1000", "0"),
                valuation(365, "500", "0")));

        assertThat(rate).isCloseTo(-0.50, within(1e-6));
    }

    @Test
    void refusesASinglePoint() {
        assertThatThrownBy(() -> MoneyWeightedReturn.annualised(List.of(valuation(0, "1000", "0"))))
                .isInstanceOf(InsufficientDataException.class);
    }

    private static Valuation valuation(int dayOffset, String netAssetValue, String externalCashFlow) {
        return new Valuation(START.plusDays(dayOffset), new BigDecimal(netAssetValue), new BigDecimal(externalCashFlow));
    }
}
