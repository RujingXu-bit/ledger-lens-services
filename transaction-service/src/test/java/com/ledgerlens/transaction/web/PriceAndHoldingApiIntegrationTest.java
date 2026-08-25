package com.ledgerlens.transaction.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlens.transaction.PostgresTestcontainerConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * The two reads analytics-service will live on from day 4, exercised end to end.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainerConfiguration.class)
class PriceAndHoldingApiIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    /**
     * The property that makes a price load safe to retry: run it twice, get the
     * same state. A batch loader that is not idempotent is a batch loader
     * nobody can safely re-run after a partial failure.
     */
    @Test
    void loadingPricesIsIdempotentAndRestatementsWin() {
        String body = """
                [
                  {"symbol":"IWDA","priceDate":"2026-01-15","closePrice":98.75,"currency":"EUR"},
                  {"symbol":"IWDA","priceDate":"2026-01-16","closePrice":99.10,"currency":"EUR"}
                ]
                """;
        assertThat(put("/api/v1/prices", body).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put("/api/v1/prices", body).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> restated = put("/api/v1/prices", """
                [{"symbol":"IWDA","priceDate":"2026-01-15","closePrice":97.00,"currency":"EUR"}]
                """);
        assertThat(restated.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> found = restTemplate.getForEntity(
                "/api/v1/prices?symbols=IWDA&from=2026-01-15&to=2026-01-16", String.class);

        assertThat(found.getBody()).contains("97.00000000").doesNotContain("98.75000000");
        // Two dates, one row each - the second load did not duplicate anything.
        assertThat(found.getBody().split("priceDate", -1)).hasSize(3);
    }

    @Test
    void holdingsComeOutOfTheLedgerWithNoHoldingsTableInSight() {
        UUID portfolio = UUID.randomUUID();
        post(trade(portfolio, "BUY", "IWDA", 10, "2026-01-05T00:00:00Z"));
        post(trade(portfolio, "BUY", "IWDA", 5, "2026-01-10T00:00:00Z"));
        post(trade(portfolio, "SELL", "IWDA", 4, "2026-02-01T00:00:00Z"));
        post(trade(portfolio, "BUY", "VWCE", 2, "2026-01-12T00:00:00Z"));

        ResponseEntity<String> now = restTemplate.getForEntity(
                "/api/v1/portfolios/{id}/holdings", String.class, portfolio);
        assertThat(now.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(now.getBody()).contains("\"symbol\":\"IWDA\"", "11.00000000");
        assertThat(now.getBody()).contains("\"symbol\":\"VWCE\"", "2.00000000");

        // Same ledger, earlier vantage point: the February sale has not happened yet.
        ResponseEntity<String> inJanuary = restTemplate.getForEntity(
                "/api/v1/portfolios/{id}/holdings?asOf=2026-01-31T00:00:00Z", String.class, portfolio);
        assertThat(inJanuary.getBody()).contains("15.00000000");
    }

    private ResponseEntity<String> put(String path, String json) {
        return restTemplate.exchange(path, HttpMethod.PUT, jsonEntity(json), String.class);
    }

    private ResponseEntity<String> post(String json) {
        return restTemplate.exchange("/api/v1/transactions", HttpMethod.POST, jsonEntity(json), String.class);
    }

    private static HttpEntity<String> jsonEntity(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(json, headers);
    }

    private static String trade(UUID portfolio, String type, String symbol, int quantity, String executedAt) {
        return """
                {
                  "portfolioId": "%s", "type": "%s", "symbol": "%s",
                  "quantity": %d, "pricePerUnit": 100, "currency": "EUR", "executedAt": "%s"
                }
                """.formatted(portfolio, type, symbol, quantity, executedAt);
    }
}
