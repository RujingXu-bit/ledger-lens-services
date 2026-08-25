package com.ledgerlens.transaction.domain;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * The composite primary key of {@link DailyPrice}, as JPA requires it: a
 * serialisable class with equals and hashCode, matching the {@code @Id} fields
 * of the entity by name and type.
 */
public class DailyPriceId implements Serializable {

    private String symbol;
    private LocalDate priceDate;

    protected DailyPriceId() {
    }

    public DailyPriceId(String symbol, LocalDate priceDate) {
        this.symbol = symbol;
        this.priceDate = priceDate;
    }

    public String getSymbol() {
        return symbol;
    }

    public LocalDate getPriceDate() {
        return priceDate;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DailyPriceId that)) {
            return false;
        }
        return Objects.equals(symbol, that.symbol) && Objects.equals(priceDate, that.priceDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(symbol, priceDate);
    }
}
