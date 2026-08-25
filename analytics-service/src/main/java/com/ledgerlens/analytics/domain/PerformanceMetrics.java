package com.ledgerlens.analytics.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The four figures this service exists to produce, plus the inputs needed to
 * interpret them.
 *
 * @param observations         number of daily returns behind the statistics, not number of days
 * @param totalReturn          time-weighted, cumulative over the window
 * @param annualisedReturn     time-weighted, geometric, scaled by 252 trading days
 * @param moneyWeightedReturn  annualised internal rate of return of the investor's own cash
 *                             flows (XIRR). Null when the flows do not define one. Answers a
 *                             different question from {@code annualisedReturn} and is reported
 *                             alongside it rather than instead of it.
 * @param annualisedVolatility sample standard deviation of daily returns, scaled by sqrt(252)
 * @param maxDrawdown          worst peak-to-trough fall of the time-weighted index; zero or negative
 * @param sharpeRatio          null when volatility is zero, because the ratio is undefined rather than infinite
 * @param riskFreeRate         echoed back deliberately: a Sharpe ratio without its risk-free rate is not comparable to anything
 */
public record PerformanceMetrics(
        LocalDate from,
        LocalDate to,
        int observations,
        BigDecimal startingValue,
        BigDecimal endingValue,
        BigDecimal totalReturn,
        BigDecimal annualisedReturn,
        BigDecimal moneyWeightedReturn,
        BigDecimal annualisedVolatility,
        BigDecimal maxDrawdown,
        BigDecimal sharpeRatio,
        BigDecimal riskFreeRate) {
}
