package com.ledgerlens.transaction.web;

import com.ledgerlens.transaction.domain.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "A transaction to record. Which fields are required depends on `type`; "
        + "see the endpoint description.")
public record CreateTransactionRequest(

        @Schema(example = "c88492cf-2ce3-49cc-809d-a83d86d67682")
        @NotNull(message = "portfolioId is required")
        UUID portfolioId,

        @Schema(example = "BUY")
        @NotNull(message = "type is required; one of BUY, SELL, DIVIDEND, DEPOSIT, WITHDRAWAL")
        TransactionType type,

        @Schema(description = "Required for BUY, SELL and DIVIDEND; must be absent otherwise. "
                + "Normalised to upper case.", example = "IWDA")
        @Pattern(regexp = "^[A-Za-z0-9.\\-]{1,16}$", message = "symbol must be 1-16 letters, digits, dots or hyphens")
        String symbol,

        // Nullable here, required by the domain for BUY and SELL. A null value
        // passes every constraint below - Bean Validation skips nulls - which
        // is exactly the division of labour intended.
        @Schema(description = "Units traded, always positive — direction is carried by `type`. "
                + "Required for BUY and SELL, must be absent otherwise.", example = "10.5")
        @Positive(message = "quantity must be positive")
        @Digits(integer = 11, fraction = 8, message = "quantity is too large or too precise")
        BigDecimal quantity,

        @Schema(description = "Execution price per unit. Required for BUY and SELL.", example = "98.75")
        @PositiveOrZero(message = "pricePerUnit cannot be negative")
        @Digits(integer = 11, fraction = 8, message = "pricePerUnit is too large or too precise")
        BigDecimal pricePerUnit,

        @Schema(description = "Commission or withholding. Always a cost: it increases the outlay on a "
                + "purchase and reduces the proceeds of a sale. Defaults to zero.", example = "1.50")
        @PositiveOrZero(message = "fee cannot be negative")
        @Digits(integer = 15, fraction = 4, message = "fee is too large or too precise")
        BigDecimal fee,

        @Schema(description = "Gross cash for DIVIDEND, DEPOSIT and WITHDRAWAL, always positive. "
                + "Must be absent for BUY and SELL, whose cash impact is derived from quantity and price.",
                example = "5000.00")
        @Positive(message = "amount must be positive")
        @Digits(integer = 15, fraction = 4, message = "amount is too large or too precise")
        BigDecimal amount,

        @Schema(description = "ISO 4217 code, normalised to upper case", example = "EUR")
        @NotNull(message = "currency is required")
        @Pattern(regexp = "^[A-Za-z]{3}$", message = "currency must be a three-letter ISO 4217 code")
        String currency,

        @Schema(description = "When the trade executed in the market. Cannot be in the future.",
                example = "2026-01-15T09:00:00Z")
        @NotNull(message = "executedAt is required")
        @PastOrPresent(message = "executedAt cannot be in the future")
        Instant executedAt
) {
}
