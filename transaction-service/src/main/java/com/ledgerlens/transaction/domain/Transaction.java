package com.ledgerlens.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;

/**
 * One immutable entry in a portfolio's ledger.
 *
 * <p>There are no setters. A transaction records something that already
 * happened, so correcting one means writing a reversing entry, not editing
 * history — the same discipline as double-entry bookkeeping, and the reason
 * every analytics figure can be recomputed from scratch at any time.
 *
 * <p>Instances are created only through {@link #of}, which enforces the rules
 * that decide whether a transaction can exist at all.
 */
@Entity
@Table(name = "transactions")
public class Transaction {

    /** Cash is money: four decimal places, rounded half-even. */
    public static final int CASH_SCALE = 4;

    /** Quantities allow fractional shares, so they need more room than cash. */
    public static final int QUANTITY_SCALE = 8;

    /**
     * Banker's rounding. HALF_UP is biased upwards and, summed over a ledger,
     * that bias becomes money that never existed.
     */
    private static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    /**
     * Hibernate generates the UUID, which keeps the identifier null until the
     * row is persisted. That matters: Spring Data's {@code save()} decides
     * between INSERT and UPDATE by asking whether the id is null, so an id
     * assigned in application code costs a needless SELECT before every insert.
     *
     * <p>Random UUIDs scatter across the primary-key index. At real volume a
     * time-ordered UUID (v7) restores insert locality; at this volume it does
     * not pay for the extra machinery.
     */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "portfolio_id", nullable = false, updatable = false)
    private UUID portfolioId;

    /**
     * Stored as its name, not its ordinal. An ordinal breaks silently the day
     * someone inserts a constant in the middle of the enum.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private TransactionType type;

    @Column(length = 16, updatable = false)
    private String symbol;

    @Column(precision = 19, scale = QUANTITY_SCALE, updatable = false)
    private BigDecimal quantity;

    @Column(name = "price_per_unit", precision = 19, scale = QUANTITY_SCALE, updatable = false)
    private BigDecimal pricePerUnit;

    @Column(nullable = false, precision = 19, scale = CASH_SCALE, updatable = false)
    private BigDecimal fee;

    @Column(name = "cash_amount", nullable = false, precision = 19, scale = CASH_SCALE, updatable = false)
    private BigDecimal cashAmount;

    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    /** When the trade happened in the market — supplied by the caller. */
    @Column(name = "executed_at", nullable = false, updatable = false)
    private Instant executedAt;

    /** When this service learned about it — never supplied by the caller. */
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Required by JPA. Not for application code. */
    protected Transaction() {
    }

    /**
     * The only way to make a transaction.
     *
     * <p>{@code amount} is the gross cash figure for the types that carry one
     * (DIVIDEND, DEPOSIT, WITHDRAWAL) and must be absent for trades, where the
     * cash figure is quantity times price. The caller never supplies the signed
     * cash amount: a client that could set it could book a purchase that
     * increases the cash balance.
     *
     * @throws InvalidTransactionException if the combination of fields cannot describe a real event
     */
    public static Transaction of(UUID portfolioId,
                                 TransactionType type,
                                 String symbol,
                                 BigDecimal quantity,
                                 BigDecimal pricePerUnit,
                                 BigDecimal fee,
                                 BigDecimal amount,
                                 String currency,
                                 Instant executedAt) {

        Objects.requireNonNull(portfolioId, "portfolioId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(executedAt, "executedAt");

        BigDecimal feeOrZero = fee == null ? BigDecimal.ZERO : fee;
        if (feeOrZero.signum() < 0) {
            throw new InvalidTransactionException("fee cannot be negative");
        }

        if (type.requiresSymbol() && (symbol == null || symbol.isBlank())) {
            throw new InvalidTransactionException(type + " requires a symbol");
        }
        if (!type.requiresSymbol() && symbol != null) {
            throw new InvalidTransactionException(type + " must not carry a symbol");
        }

        BigDecimal cashAmount;
        if (type.isSecurityTrade()) {
            if (quantity == null || pricePerUnit == null) {
                throw new InvalidTransactionException(type + " requires quantity and pricePerUnit");
            }
            if (quantity.signum() <= 0) {
                throw new InvalidTransactionException("quantity must be positive; direction is carried by the type");
            }
            if (pricePerUnit.signum() < 0) {
                throw new InvalidTransactionException("pricePerUnit cannot be negative");
            }
            if (amount != null) {
                throw new InvalidTransactionException(type + " derives its cash amount from quantity and price; do not send amount");
            }
            BigDecimal gross = quantity.multiply(pricePerUnit).setScale(CASH_SCALE, ROUNDING);
            // The fee is always a cost, so it reduces proceeds and increases outlay.
            cashAmount = type == TransactionType.BUY
                    ? gross.add(feeOrZero).negate()
                    : gross.subtract(feeOrZero);
        } else {
            if (quantity != null || pricePerUnit != null) {
                throw new InvalidTransactionException(type + " must not carry quantity or pricePerUnit");
            }
            if (amount == null || amount.signum() <= 0) {
                throw new InvalidTransactionException(type + " requires a positive amount");
            }
            BigDecimal net = amount.setScale(CASH_SCALE, ROUNDING).subtract(feeOrZero);
            cashAmount = type == TransactionType.WITHDRAWAL ? net.negate() : net;
        }

        Transaction t = new Transaction();
        t.portfolioId = portfolioId;
        t.type = type;
        // Locale.ROOT, not the platform default. In a Turkish locale "iwda"
        // upper-cases to "İWDA" with a dotted capital I, and the symbol stops
        // matching anything. The JVM's default locale comes from the
        // environment, which a container image is free to change under you.
        t.symbol = symbol == null ? null : symbol.toUpperCase(Locale.ROOT);
        t.quantity = quantity == null ? null : quantity.setScale(QUANTITY_SCALE, ROUNDING);
        t.pricePerUnit = pricePerUnit == null ? null : pricePerUnit.setScale(QUANTITY_SCALE, ROUNDING);
        t.fee = feeOrZero.setScale(CASH_SCALE, ROUNDING);
        t.cashAmount = cashAmount;
        t.currency = currency.toUpperCase(Locale.ROOT);
        t.executedAt = executedAt;
        return t;
    }

    public UUID getId() {
        return id;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public TransactionType getType() {
        return type;
    }

    public String getSymbol() {
        return symbol;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public BigDecimal getPricePerUnit() {
        return pricePerUnit;
    }

    public BigDecimal getFee() {
        return fee;
    }

    public BigDecimal getCashAmount() {
        return cashAmount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getExecutedAt() {
        return executedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /**
     * Identity is the database id and nothing else. Deliberately not Lombok's
     * or the IDE's all-fields equals: two transactions with identical values
     * are two distinct events, and an entity whose hashCode depends on mutable
     * fields corrupts any HashSet it is placed in before it is saved.
     */
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof Transaction that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Transaction.class.hashCode();
    }

    @Override
    public String toString() {
        return "Transaction[id=%s, portfolio=%s, %s %s, cash=%s %s, executedAt=%s]"
                .formatted(id, portfolioId, type, symbol, cashAmount, currency, executedAt);
    }
}
