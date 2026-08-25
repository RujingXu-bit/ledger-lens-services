package com.ledgerlens.analytics.domain;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A closing price for one symbol on one trading day. */
public record PriceRecord(String symbol, LocalDate priceDate, BigDecimal closePrice) {
}
