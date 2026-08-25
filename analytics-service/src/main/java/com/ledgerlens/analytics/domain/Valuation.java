package com.ledgerlens.analytics.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What a portfolio was worth at the close of one trading day, and how much money
 * crossed its boundary that day.
 *
 * @param netAssetValue    cash balance plus the market value of every position
 * @param externalCashFlow deposits minus withdrawals for the day; signed, and
 *                         subtracted before the day's return is computed
 */
public record Valuation(LocalDate date, BigDecimal netAssetValue, BigDecimal externalCashFlow) {
}
