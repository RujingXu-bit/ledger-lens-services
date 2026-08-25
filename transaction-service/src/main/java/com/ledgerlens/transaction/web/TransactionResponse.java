package com.ledgerlens.transaction.web;

import com.ledgerlens.transaction.domain.Transaction;
import com.ledgerlens.transaction.domain.TransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The published shape of a transaction. This record is the API contract, and
 * from day 5 it is also what analytics-service parses, so removing or renaming
 * a field here breaks another service.
 */
public record TransactionResponse(
        UUID id,
        UUID portfolioId,
        TransactionType type,
        @Schema(description = "Null for DEPOSIT and WITHDRAWAL", example = "IWDA") String symbol,
        @Schema(description = "Null for cash movements", example = "10.50000000") BigDecimal quantity,
        @Schema(description = "Null for cash movements", example = "98.75000000") BigDecimal pricePerUnit,
        @Schema(example = "1.5000") BigDecimal fee,
        @Schema(description = "The signed cash impact, computed by the server: negative when money leaves "
                + "the portfolio. Summing this column over a portfolio gives its cash balance.",
                example = "-1038.3750") BigDecimal cashAmount,
        String currency,
        @Schema(description = "When it executed in the market — supplied by the caller") Instant executedAt,
        @Schema(description = "When this service recorded it — never supplied by the caller") Instant createdAt
) {

    public static TransactionResponse from(Transaction t) {
        return new TransactionResponse(
                t.getId(),
                t.getPortfolioId(),
                t.getType(),
                t.getSymbol(),
                t.getQuantity(),
                t.getPricePerUnit(),
                t.getFee(),
                t.getCashAmount(),
                t.getCurrency(),
                t.getExecutedAt(),
                t.getCreatedAt());
    }
}
