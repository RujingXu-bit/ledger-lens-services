package com.ledgerlens.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * A security's closing price on a trading day.
 *
 * <p>Read-only from the entity's point of view — new values arrive through the
 * bulk upsert, not by mutating a loaded instance. transaction-service stores
 * this because analytics-service must stay stateless and something has to own
 * the data.
 *
 * <p>The honest caveat, worth saying out loud rather than hiding: market data
 * is arguably its own bounded context and in a larger system would be its own
 * service, fed by a vendor feed. Folding it in here is a deliberate scope
 * decision for a two-week project, not a claim that it belongs here.
 */
@Entity
@Table(name = "daily_prices")
@IdClass(DailyPriceId.class)
public class DailyPrice {

    @Id
    @Column(length = 16, nullable = false)
    private String symbol;

    @Id
    @Column(name = "price_date", nullable = false)
    private LocalDate priceDate;

    @Column(name = "close_price", nullable = false, precision = 19, scale = 8)
    private BigDecimal closePrice;

    @Column(nullable = false, length = 3)
    private String currency;

    /** When this row was last written. A restated price is an update, not a new row. */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DailyPrice() {
    }

    public String getSymbol() {
        return symbol;
    }

    public LocalDate getPriceDate() {
        return priceDate;
    }

    public BigDecimal getClosePrice() {
        return closePrice;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
