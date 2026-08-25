package com.ledgerlens.transaction.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ledgerlens.transaction.domain.InvalidTransactionException;
import com.ledgerlens.transaction.domain.Transaction;
import com.ledgerlens.transaction.domain.TransactionNotFoundException;
import com.ledgerlens.transaction.domain.TransactionType;
import com.ledgerlens.transaction.service.TransactionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The web layer alone: routing, deserialisation, validation, status codes and
 * error bodies. The service is mocked and no database is involved, so these run
 * in milliseconds and fail for exactly one reason — the HTTP contract changed.
 */
@WebMvcTest(TransactionController.class)
class TransactionControllerTest {

    private static final UUID PORTFOLIO = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TransactionService service;

    @Test
    void createReturns201WithLocationHeader() throws Exception {
        Transaction saved = Transaction.of(PORTFOLIO, TransactionType.BUY, "IWDA",
                new BigDecimal("10"), new BigDecimal("98.75"), new BigDecimal("1.50"),
                null, "EUR", Instant.parse("2026-01-15T09:00:00Z"));
        when(service.record(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(saved);

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "portfolioId": "11111111-1111-1111-1111-111111111111",
                                  "type": "BUY",
                                  "symbol": "IWDA",
                                  "quantity": 10,
                                  "pricePerUnit": 98.75,
                                  "fee": 1.50,
                                  "currency": "EUR",
                                  "executedAt": "2026-01-15T09:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.cashAmount").value(-989.0000))
                .andExpect(jsonPath("$.type").value("BUY"));
    }

    @Test
    void missingRequiredFieldsAreAllReportedAtOnceAs400ProblemDetail() throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "type": "BUY", "symbol": "IWDA", "quantity": 10, "pricePerUnit": 98.75 }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PROBLEM_JSON_VALUE))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.portfolioId").exists())
                .andExpect(jsonPath("$.errors.currency").exists())
                .andExpect(jsonPath("$.errors.executedAt").exists());
    }

    @Test
    void aTradeDatedInTheFutureIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "portfolioId": "11111111-1111-1111-1111-111111111111",
                                  "type": "DEPOSIT",
                                  "amount": 100,
                                  "currency": "EUR",
                                  "executedAt": "2099-01-01T00:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.executedAt").exists());
    }

    /** Well-formed JSON, impossible transaction: 422, not 400. */
    @Test
    void domainRuleBreachIs422() throws Exception {
        when(service.record(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new InvalidTransactionException("BUY requires a symbol"));

        mockMvc.perform(post("/api/v1/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "portfolioId": "11111111-1111-1111-1111-111111111111",
                                  "type": "BUY",
                                  "quantity": 10,
                                  "pricePerUnit": 98.75,
                                  "currency": "EUR",
                                  "executedAt": "2026-01-15T09:00:00Z"
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.title").value("Invalid transaction"))
                .andExpect(jsonPath("$.detail").value("BUY requires a symbol"));
    }

    @Test
    void unknownIdIs404() throws Exception {
        UUID missing = UUID.randomUUID();
        when(service.findById(eq(missing))).thenThrow(new TransactionNotFoundException(missing));

        mockMvc.perform(get("/api/v1/transactions/{id}", missing))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.transactionId").value(missing.toString()));
    }

    @Test
    void malformedUuidInThePathIs400NotAStackTrace() throws Exception {
        mockMvc.perform(get("/api/v1/transactions/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listRequiresAPortfolioIdSoItCanNeverReturnEveryPortfolio() throws Exception {
        mockMvc.perform(get("/api/v1/transactions"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pageSizeAboveTheCeilingIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/transactions")
                        .param("portfolioId", PORTFOLIO.toString())
                        .param("size", "5000"))
                .andExpect(status().isBadRequest());
    }
}
