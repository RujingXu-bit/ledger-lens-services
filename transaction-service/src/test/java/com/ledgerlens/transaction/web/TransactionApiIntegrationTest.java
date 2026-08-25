package com.ledgerlens.transaction.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlens.transaction.PostgresTestcontainerConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * End to end through the real stack: HTTP in, Postgres out, nothing mocked.
 *
 * <p>The unit tests prove the arithmetic and the slice tests prove the contract;
 * this proves they are wired to each other. It is the test that catches a
 * mapping that drops a field, a column that silently truncates a scale, or a
 * transaction boundary that never commits.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainerConfiguration.class)
class TransactionApiIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void recordsABuyThenReadsItBackThroughTheApi() {
        UUID portfolio = UUID.randomUUID();

        ResponseEntity<String> created = post("""
                {
                  "portfolioId": "%s",
                  "type": "BUY",
                  "symbol": "iwda",
                  "quantity": 10,
                  "pricePerUnit": 98.75,
                  "fee": 1.50,
                  "currency": "eur",
                  "executedAt": "2026-01-15T09:00:00Z"
                }
                """.formatted(portfolio));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).contains("\"cashAmount\":-989.0000");
        // Normalised on the way in, not on the way out.
        assertThat(created.getBody()).contains("\"symbol\":\"IWDA\"");
        assertThat(created.getBody()).contains("\"currency\":\"EUR\"");

        String location = created.getHeaders().getFirst(HttpHeaders.LOCATION);
        assertThat(location).isNotNull();

        ResponseEntity<String> fetched = restTemplate.getForEntity(location, String.class);
        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).contains("\"cashAmount\":-989.0000");
    }

    @Test
    void portfolioLedgerComesBackOldestFirst() {
        UUID portfolio = UUID.randomUUID();
        post(deposit(portfolio, "2026-03-01T00:00:00Z", 300));
        post(deposit(portfolio, "2026-01-01T00:00:00Z", 100));
        post(deposit(portfolio, "2026-02-01T00:00:00Z", 200));

        ResponseEntity<String> list = restTemplate.getForEntity(
                "/api/v1/transactions?portfolioId={id}", String.class, portfolio);

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = list.getBody();
        assertThat(body).isNotNull();
        assertThat(body.indexOf("100.0000")).isLessThan(body.indexOf("200.0000"));
        assertThat(body.indexOf("200.0000")).isLessThan(body.indexOf("300.0000"));
    }

    @Test
    void aBuyWithoutASymbolIs422AndNothingIsWritten() {
        UUID portfolio = UUID.randomUUID();

        ResponseEntity<String> response = post("""
                {
                  "portfolioId": "%s",
                  "type": "BUY",
                  "quantity": 10,
                  "pricePerUnit": 98.75,
                  "currency": "EUR",
                  "executedAt": "2026-01-15T09:00:00Z"
                }
                """.formatted(portfolio));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);

        ResponseEntity<String> list = restTemplate.getForEntity(
                "/api/v1/transactions?portfolioId={id}", String.class, portfolio);
        assertThat(list.getBody()).isEqualTo("[]");
    }

    private ResponseEntity<String> post(String json) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                "/api/v1/transactions", HttpMethod.POST, new HttpEntity<>(json, headers), String.class);
    }

    private static String deposit(UUID portfolio, String executedAt, int amount) {
        return """
                {
                  "portfolioId": "%s",
                  "type": "DEPOSIT",
                  "amount": %d,
                  "currency": "EUR",
                  "executedAt": "%s"
                }
                """.formatted(portfolio, amount, executedAt);
    }
}
