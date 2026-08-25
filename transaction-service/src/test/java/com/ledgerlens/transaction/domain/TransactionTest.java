package com.ledgerlens.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Pure unit tests of the domain rules — no Spring, no database, milliseconds to
 * run. The rules that decide whether money is right belong in the fastest test
 * tier there is, so they can be exhaustive without anyone minding.
 */
class TransactionTest {

    private static final UUID PORTFOLIO = UUID.randomUUID();
    private static final Instant EXECUTED = Instant.parse("2026-03-02T10:15:30Z");

    @Nested
    @DisplayName("cash amount")
    class CashAmount {

        @Test
        void buyCostsQuantityTimesPricePlusFee() {
            Transaction t = Transaction.of(PORTFOLIO, TransactionType.BUY, "IWDA",
                    new BigDecimal("10"), new BigDecimal("98.75"), new BigDecimal("1.50"),
                    null, "EUR", EXECUTED);

            // 10 * 98.75 = 987.50, plus a 1.50 fee, leaving the portfolio.
            assertThat(t.getCashAmount()).isEqualByComparingTo("-989.00");
        }

        @Test
        void sellProceedsAreNetOfFee() {
            Transaction t = Transaction.of(PORTFOLIO, TransactionType.SELL, "IWDA",
                    new BigDecimal("10"), new BigDecimal("98.75"), new BigDecimal("1.50"),
                    null, "EUR", EXECUTED);

            assertThat(t.getCashAmount()).isEqualByComparingTo("986.00");
        }

        @Test
        void withdrawalIsNegativeAndDepositIsPositive() {
            Transaction deposit = Transaction.of(PORTFOLIO, TransactionType.DEPOSIT, null,
                    null, null, null, new BigDecimal("5000"), "EUR", EXECUTED);
            Transaction withdrawal = Transaction.of(PORTFOLIO, TransactionType.WITHDRAWAL, null,
                    null, null, null, new BigDecimal("1200"), "EUR", EXECUTED);

            assertThat(deposit.getCashAmount()).isEqualByComparingTo("5000.0000");
            assertThat(withdrawal.getCashAmount()).isEqualByComparingTo("-1200.0000");
        }

        @Test
        void dividendIsNetOfWithholdingBookedAsFee() {
            Transaction t = Transaction.of(PORTFOLIO, TransactionType.DIVIDEND, "VWCE",
                    null, null, new BigDecimal("2.25"), new BigDecimal("15.00"), "EUR", EXECUTED);

            assertThat(t.getCashAmount()).isEqualByComparingTo("12.75");
        }

        @Test
        void roundsHalfEvenSoRepeatedHalvesDoNotDriftUpwards() {
            // 3 * 0.12500 = 0.375 -> 0.3750 at four places is exact, so push the
            // product to a genuine half at the fourth decimal: 1.00005 * 3 = 3.00015
            Transaction t = Transaction.of(PORTFOLIO, TransactionType.BUY, "ACME",
                    new BigDecimal("3"), new BigDecimal("1.00005"), BigDecimal.ZERO,
                    null, "EUR", EXECUTED);

            // 3.00015 rounds to 3.0002 (nearest even at the last kept digit is 2).
            assertThat(t.getCashAmount()).isEqualByComparingTo("-3.0002");
        }
    }

    @Nested
    @DisplayName("rules that make a transaction impossible")
    class Invariants {

        @ParameterizedTest
        @EnumSource(value = TransactionType.class, names = {"BUY", "SELL"})
        void securityTradesRequireSymbolQuantityAndPrice(TransactionType type) {
            assertThatThrownBy(() -> Transaction.of(PORTFOLIO, type, null,
                    new BigDecimal("1"), new BigDecimal("1"), null, null, "EUR", EXECUTED))
                    .isInstanceOf(InvalidTransactionException.class)
                    .hasMessageContaining("symbol");

            assertThatThrownBy(() -> Transaction.of(PORTFOLIO, type, "IWDA",
                    null, new BigDecimal("1"), null, null, "EUR", EXECUTED))
                    .isInstanceOf(InvalidTransactionException.class)
                    .hasMessageContaining("quantity");
        }

        @Test
        void cashMovementsMustNotCarryASymbol() {
            assertThatThrownBy(() -> Transaction.of(PORTFOLIO, TransactionType.DEPOSIT, "IWDA",
                    null, null, null, new BigDecimal("100"), "EUR", EXECUTED))
                    .isInstanceOf(InvalidTransactionException.class)
                    .hasMessageContaining("must not carry a symbol");
        }

        @Test
        void clientsCannotDictateTheCashAmountOfATrade() {
            assertThatThrownBy(() -> Transaction.of(PORTFOLIO, TransactionType.BUY, "IWDA",
                    new BigDecimal("10"), new BigDecimal("98.75"), null,
                    new BigDecimal("1"), "EUR", EXECUTED))
                    .isInstanceOf(InvalidTransactionException.class)
                    .hasMessageContaining("do not send amount");
        }

        @Test
        void negativeQuantityIsRejectedBecauseDirectionIsCarriedByTheType() {
            assertThatThrownBy(() -> Transaction.of(PORTFOLIO, TransactionType.SELL, "IWDA",
                    new BigDecimal("-10"), new BigDecimal("98.75"), null, null, "EUR", EXECUTED))
                    .isInstanceOf(InvalidTransactionException.class)
                    .hasMessageContaining("quantity must be positive");
        }

        @Test
        void feeCannotBeNegativeBecauseThatWouldBeIncome() {
            assertThatThrownBy(() -> Transaction.of(PORTFOLIO, TransactionType.BUY, "IWDA",
                    new BigDecimal("10"), new BigDecimal("98.75"), new BigDecimal("-1"),
                    null, "EUR", EXECUTED))
                    .isInstanceOf(InvalidTransactionException.class)
                    .hasMessageContaining("fee cannot be negative");
        }
    }

    @Test
    void symbolAndCurrencyAreNormalisedToUpperCase() {
        Transaction t = Transaction.of(PORTFOLIO, TransactionType.BUY, "iwda",
                new BigDecimal("1"), new BigDecimal("100"), null, null, "eur", EXECUTED);

        assertThat(t.getSymbol()).isEqualTo("IWDA");
        assertThat(t.getCurrency()).isEqualTo("EUR");
    }
}
