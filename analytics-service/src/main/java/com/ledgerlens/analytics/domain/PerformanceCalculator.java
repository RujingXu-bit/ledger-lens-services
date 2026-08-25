package com.ledgerlens.analytics.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The maths. Pure functions over a valuation series — no Spring, no I/O, no
 * clock, so every rule below is testable in microseconds and the tests can be
 * exhaustive without anyone minding.
 *
 * <h2>On BigDecimal versus double</h2>
 * Money stays {@link BigDecimal} end to end: a cash balance must be exact.
 * The statistics are computed in {@code double}, because standard deviation and
 * geometric annualisation need {@code sqrt}, {@code log} and {@code pow}, which
 * BigDecimal either lacks or only approximates anyway. Sixteen significant
 * digits on a volatility figure is already far more precision than the
 * underlying prices justify. The results are converted back to BigDecimal at
 * the boundary so the API never publishes a float.
 */
public final class PerformanceCalculator {

    /** Trading days in a year. The conventional constant for annualising daily figures. */
    public static final int TRADING_DAYS_PER_YEAR = 252;

    private static final int RATE_SCALE = 6;
    private static final MathContext MC = MathContext.DECIMAL64;

    private PerformanceCalculator() {
    }

    /**
     * @param riskFreeRate annualised, as a decimal (0.025 is 2.5%). Never defaulted
     *                     silently inside the formula: the caller states the assumption.
     */
    public static PerformanceMetrics evaluate(List<Valuation> series, BigDecimal riskFreeRate) {
        if (series.size() < 2) {
            throw new InsufficientDataException(
                    "At least two valuation points are needed; got " + series.size());
        }

        double[] dailyReturns = dailyReturns(series);
        double totalReturn = chainLink(dailyReturns);
        double volatility = annualisedVolatility(dailyReturns);
        double annualised = annualise(totalReturn, dailyReturns.length);

        Double sharpe = volatility == 0.0 ? null : (annualised - riskFreeRate.doubleValue()) / volatility;

        // Reported beside the time-weighted figure, never in place of it. The two
        // answer different questions and a reader who cannot see both has no way
        // to tell which one they are looking at.
        Double moneyWeighted = MoneyWeightedReturn.annualised(series);

        return new PerformanceMetrics(
                series.getFirst().date(),
                series.getLast().date(),
                dailyReturns.length,
                series.getFirst().netAssetValue(),
                series.getLast().netAssetValue(),
                rate(totalReturn),
                rate(annualised),
                moneyWeighted == null ? null : rate(moneyWeighted),
                rate(volatility),
                rate(maxDrawdown(dailyReturns)),
                sharpe == null ? null : rate(sharpe),
                riskFreeRate);
    }

    /**
     * The daily time-weighted return, with external cash flows removed:
     *
     * <pre>  r(t) = (NAV(t) - NAV(t-1) - CF(t)) / NAV(t-1)</pre>
     *
     * <p>Subtracting {@code CF(t)} is the whole trick. Without it a 1,500 EUR
     * contribution into a 50,000 EUR portfolio reads as a 3% gain, and the
     * measurement rewards saving rather than investing.
     *
     * <p>Cash flows are treated as arriving at the close, so the money does not
     * earn a return on the day it lands. The alternative convention (start of
     * day) is equally defensible; what matters is choosing one and saying so.
     */
    static double[] dailyReturns(List<Valuation> series) {
        List<Double> returns = new ArrayList<>(series.size() - 1);
        for (int i = 1; i < series.size(); i++) {
            BigDecimal previous = series.get(i - 1).netAssetValue();
            if (previous.signum() == 0) {
                // An empty portfolio has no return to speak of; the day money
                // first arrives is a flow, not a gain.
                returns.add(0.0);
                continue;
            }
            BigDecimal change = series.get(i).netAssetValue()
                    .subtract(previous)
                    .subtract(series.get(i).externalCashFlow());
            returns.add(change.divide(previous, MC).doubleValue());
        }
        return returns.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** Cumulative time-weighted return: the product of (1 + r), minus one. */
    static double chainLink(double[] dailyReturns) {
        double growth = 1.0;
        for (double daily : dailyReturns) {
            growth *= (1.0 + daily);
        }
        return growth - 1.0;
    }

    /**
     * Geometric annualisation. Not {@code totalReturn * 252 / n}: returns
     * compound, and the arithmetic version overstates anything volatile.
     */
    static double annualise(double totalReturn, int observations) {
        double years = (double) observations / TRADING_DAYS_PER_YEAR;
        if (years == 0) {
            return 0.0;
        }
        // A total loss leaves nothing to compound, so the annual rate is -100%
        // however long the period; pow of a negative base would be NaN.
        if (1.0 + totalReturn <= 0.0) {
            return -1.0;
        }
        return Math.pow(1.0 + totalReturn, 1.0 / years) - 1.0;
    }

    /**
     * Sample standard deviation (n-1), scaled by sqrt(252).
     *
     * <p>The n-1 denominator is not pedantry: these returns are a sample of the
     * process that generated them, not the entire population, and dividing by n
     * biases the estimate downwards — which flatters every Sharpe ratio built
     * on it.
     */
    static double annualisedVolatility(double[] dailyReturns) {
        if (dailyReturns.length < 2) {
            throw new InsufficientDataException("Volatility needs at least two returns");
        }
        double mean = 0.0;
        for (double daily : dailyReturns) {
            mean += daily;
        }
        mean /= dailyReturns.length;

        double sumSquares = 0.0;
        for (double daily : dailyReturns) {
            sumSquares += (daily - mean) * (daily - mean);
        }
        return Math.sqrt(sumSquares / (dailyReturns.length - 1)) * Math.sqrt(TRADING_DAYS_PER_YEAR);
    }

    /**
     * Worst peak-to-trough fall, expressed as a negative fraction.
     *
     * <p>Measured on the time-weighted index built from the returns, never on
     * net asset value directly. A withdrawal lowers NAV without losing anyone a
     * cent; on a NAV series it would register as a drawdown, and a portfolio
     * paying a monthly income would look like a disaster.
     */
    static double maxDrawdown(double[] dailyReturns) {
        double index = 1.0;
        double peak = 1.0;
        double worst = 0.0;
        for (double daily : dailyReturns) {
            index *= (1.0 + daily);
            peak = Math.max(peak, index);
            worst = Math.min(worst, index / peak - 1.0);
        }
        return worst;
    }

    private static BigDecimal rate(double value) {
        return BigDecimal.valueOf(value).setScale(RATE_SCALE, RoundingMode.HALF_EVEN);
    }
}
