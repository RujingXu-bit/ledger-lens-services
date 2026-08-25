package com.ledgerlens.analytics.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * The money-weighted return: the internal rate of return of the investor's own
 * cash flows, annualised. Excel calls it XIRR.
 *
 * <h2>What it answers, and why it is not the same question</h2>
 * The time-weighted return measures the <em>portfolio</em>: it strips out the
 * timing of contributions, so it is what you compare against a benchmark or
 * another manager. The money-weighted return measures the <em>investor</em>: it
 * is the rate that makes the actual sequence of payments in and out balance
 * against the final value, so it reflects whether the money happened to be in
 * the market at good times.
 *
 * <p>Both are correct answers to different questions, and they can disagree
 * sharply. Someone who contributed monthly into a market that fell and then
 * recovered will have a money-weighted return well above the time-weighted one,
 * because most of their money went in cheap. Reporting only one figure, without
 * saying which, is how performance discussions go wrong.
 *
 * <p>The four risk figures deliberately stay built on the time-weighted series:
 * a Sharpe ratio needs a periodic return series, and this method produces a
 * single scalar.
 */
public final class MoneyWeightedReturn {

    /** ACT/365 fixed. The convention has to be stated; this is the common one for XIRR. */
    private static final double DAYS_PER_YEAR = 365.0;

    /** A rate below -100% is not meaningful: you cannot lose more than everything. */
    private static final double LOWER_BOUND = -0.9999;

    /** 10,000% a year. Wide enough for any real portfolio, and for most unreal ones. */
    private static final double UPPER_BOUND = 100.0;

    private static final int MAX_ITERATIONS = 200;
    private static final double TOLERANCE = 1e-10;

    private MoneyWeightedReturn() {
    }

    /**
     * @return the annualised money-weighted return, or null when the cash flows
     *         do not define one — see {@link #solve}
     */
    public static Double annualised(List<Valuation> series) {
        if (series.size() < 2) {
            throw new InsufficientDataException("Money-weighted return needs at least two valuation points");
        }
        List<Flow> flows = flowsFromInvestorsPerspective(series);
        return solve(flows);
    }

    /**
     * Signs are from the investor's point of view, which is the opposite of the
     * portfolio's: money paid in is negative, because it left the investor.
     *
     * <ul>
     *   <li>the opening value is capital already committed, so it is an outflow at t0</li>
     *   <li>deposits during the window are outflows, withdrawals are inflows</li>
     *   <li>the closing value is what the investor would have if they stopped, so it
     *       is an inflow at the end</li>
     * </ul>
     *
     * <p>The flow on the first day is deliberately skipped: it is already inside
     * the opening value, and counting it twice would understate the return.
     */
    private static List<Flow> flowsFromInvestorsPerspective(List<Valuation> series) {
        LocalDate start = series.getFirst().date();
        List<Flow> flows = new ArrayList<>(series.size());

        flows.add(new Flow(0.0, -series.getFirst().netAssetValue().doubleValue()));

        for (int i = 1; i < series.size(); i++) {
            Valuation valuation = series.get(i);
            BigDecimal flow = valuation.externalCashFlow();
            if (flow.signum() != 0) {
                flows.add(new Flow(years(start, valuation.date()), -flow.doubleValue()));
            }
        }

        Valuation last = series.getLast();
        flows.add(new Flow(years(start, last.date()), last.netAssetValue().doubleValue()));
        return flows;
    }

    /**
     * Bisection, not Newton-Raphson.
     *
     * <p>Newton converges faster when it converges, but the NPV curve for a
     * cash-flow series with sign changes can send it off to a nonsensical rate
     * or oscillate without settling, and a wrong performance figure that looks
     * plausible is the worst possible output. Bisection cannot diverge: given a
     * sign change it halves the interval every step, so 200 iterations pin the
     * answer far tighter than the inputs deserve. The cost is microseconds on a
     * list this short.
     *
     * @return null when NPV does not change sign across the search interval. That
     *         happens when every flow points the same way — a portfolio that was
     *         only ever paid into and is now worthless, for instance. There is no
     *         rate that balances those, and inventing one would be worse than
     *         admitting it.
     */
    private static Double solve(List<Flow> flows) {
        double low = LOWER_BOUND;
        double high = UPPER_BOUND;
        double npvLow = netPresentValue(flows, low);
        double npvHigh = netPresentValue(flows, high);

        if (Double.isNaN(npvLow) || Double.isNaN(npvHigh) || npvLow * npvHigh > 0) {
            return null;
        }

        for (int i = 0; i < MAX_ITERATIONS; i++) {
            double mid = (low + high) / 2.0;
            double npvMid = netPresentValue(flows, mid);
            if (Math.abs(npvMid) < TOLERANCE || (high - low) / 2.0 < TOLERANCE) {
                return mid;
            }
            if (npvLow * npvMid <= 0) {
                high = mid;
            } else {
                low = mid;
                npvLow = npvMid;
            }
        }
        return (low + high) / 2.0;
    }

    private static double netPresentValue(List<Flow> flows, double rate) {
        double total = 0.0;
        for (Flow flow : flows) {
            total += flow.amount() / Math.pow(1.0 + rate, flow.years());
        }
        return total;
    }

    private static double years(LocalDate from, LocalDate to) {
        return ChronoUnit.DAYS.between(from, to) / DAYS_PER_YEAR;
    }

    private record Flow(double years, double amount) {
    }
}
