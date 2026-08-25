package com.ledgerlens.transaction.web;

import com.ledgerlens.transaction.domain.TransactionType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The wire format for creating a transaction — a separate type from the entity
 * on purpose.
 *
 * <p>Exposing the entity directly would let a client set the generated id, the
 * created timestamp and the derived cash amount, and would tie the published
 * API to the database schema so that renaming a column becomes a breaking API
 * change. The mapping cost is a few lines; the coupling cost is permanent.
 *
 * <p>The annotations here check <em>shape</em> only: is the field present, is
 * the number positive, does it fit the column. Which combinations of fields
 * make sense is a domain rule and lives in {@code Transaction.of}. Shape
 * failures are 400; domain failures are 422.
 */
public record CreateTransactionRequest(

        @NotNull(message = "portfolioId is required")
        UUID portfolioId,

        @NotNull(message = "type is required; one of BUY, SELL, DIVIDEND, DEPOSIT, WITHDRAWAL")
        TransactionType type,

        @Pattern(regexp = "^[A-Za-z0-9.\\-]{1,16}$", message = "symbol must be 1-16 letters, digits, dots or hyphens")
        String symbol,

        // Nullable here, required by the domain for BUY and SELL. A null value
        // passes every constraint below - Bean Validation skips nulls - which
        // is exactly the division of labour intended.
        @Positive(message = "quantity must be positive")
        @Digits(integer = 11, fraction = 8, message = "quantity is too large or too precise")
        BigDecimal quantity,

        @PositiveOrZero(message = "pricePerUnit cannot be negative")
        @Digits(integer = 11, fraction = 8, message = "pricePerUnit is too large or too precise")
        BigDecimal pricePerUnit,

        @PositiveOrZero(message = "fee cannot be negative")
        @Digits(integer = 15, fraction = 4, message = "fee is too large or too precise")
        BigDecimal fee,

        /** Gross cash for DIVIDEND, DEPOSIT and WITHDRAWAL. Must be absent for trades. */
        @Positive(message = "amount must be positive")
        @Digits(integer = 15, fraction = 4, message = "amount is too large or too precise")
        BigDecimal amount,

        @NotNull(message = "currency is required")
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "currency must be a three-letter ISO 4217 code")
        String currency,

        @NotNull(message = "executedAt is required")
        @PastOrPresent(message = "executedAt cannot be in the future")
        Instant executedAt
) {
}
