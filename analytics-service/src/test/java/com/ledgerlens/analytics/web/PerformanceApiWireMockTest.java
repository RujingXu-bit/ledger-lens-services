package com.ledgerlens.analytics.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The inter-service call, with transaction-service played by WireMock.
 *
 * <p>The value is not the happy path — it is that a stub can be made to hang,
 * to return a 500, or to drop the connection on demand. Those are the cases
 * that decide whether this service degrades or falls over, and they cannot be
 * provoked reliably against a real upstream.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "ledgerlens.transaction-service.read-timeout=1s",
        "ledgerlens.transaction-service.connect-timeout=1s"
})
class PerformanceApiWireMockTest {

    @RegisterExtension
    static WireMockExtension upstream = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void pointAnalyticsAtTheStub(DynamicPropertyRegistry registry) {
        registry.add("ledgerlens.transaction-service.base-url", upstream::baseUrl);
    }

    @Autowired
    private TestRestTemplate restTemplate;

    private UUID portfolio;

    @BeforeEach
    void freshPortfolioPerTest() {
        // The fallback cache is a singleton that outlives a test method, so each
        // test asks about a different portfolio rather than sharing state.
        portfolio = UUID.randomUUID();
    }

    @Test
    void computesPerformanceFromWhatTheUpstreamReturns() {
        stubLedgerAndPrices();

        ResponseEntity<String> response = performance();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"totalReturn\":-0.010000")
                .contains("\"observations\":2")
                .contains("\"returnMethod\":\"TIME_WEIGHTED\"")
                .contains("\"stale\":false");
    }

    /**
     * Forward compatibility: transaction-service publishes eleven fields and
     * this service reads five. Adding a twelfth upstream must not be a
     * deployment ordering problem.
     */
    @Test
    void ignoresFieldsItDoesNotKnowAbout() {
        upstream.stubFor(get(urlPathEqualTo("/api/v1/transactions")).willReturn(okJson(
                UpstreamStubs.ledger(portfolio).replace("\"currency\": \"EUR\"",
                        "\"currency\": \"EUR\", \"settlementDate\": \"2026-01-07\", \"venue\": \"XDUB\""))));
        upstream.stubFor(get(urlPathEqualTo("/api/v1/prices")).willReturn(okJson(UpstreamStubs.prices())));

        assertThat(performance().getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a hanging upstream is cut off by the read timeout, not left to hang")
    void aSlowUpstreamTimesOutRatherThanBlockingForever() {
        upstream.stubFor(get(urlPathEqualTo("/api/v1/transactions"))
                .willReturn(aResponse().withFixedDelay(4_000).withStatus(200)));

        long startedAt = System.nanoTime();
        ResponseEntity<String> response = performance();
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("30");
        // Two attempts at a one-second timeout: seconds, not the minutes an
        // operating-system default would have cost.
        assertThat(elapsedMillis).isLessThan(3_500);
    }

    @Test
    void aServerErrorUpstreamBecomes503HereRatherThan500() {
        upstream.stubFor(get(urlPathEqualTo("/api/v1/transactions"))
                .willReturn(aResponse().withStatus(500)));

        ResponseEntity<String> response = performance();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody())
                .contains("\"title\":\"Upstream unavailable\"")
                // The upstream's own error text must not leak to our callers.
                .doesNotContain("500");
    }

    @Test
    void oneDroppedConnectionIsAbsorbedByASingleRetry() {
        upstream.stubFor(get(urlPathEqualTo("/api/v1/transactions"))
                .inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("recovered"));
        upstream.stubFor(get(urlPathEqualTo("/api/v1/transactions"))
                .inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(okJson(UpstreamStubs.ledger(portfolio))));
        upstream.stubFor(get(urlPathEqualTo("/api/v1/prices")).willReturn(okJson(UpstreamStubs.prices())));

        assertThat(performance().getStatusCode()).isEqualTo(HttpStatus.OK);
        upstream.verify(2, getRequestedFor(urlPathEqualTo("/api/v1/transactions")));
    }

    /**
     * The fallback. A clearly-labelled figure from a few seconds ago is more
     * useful to a dashboard than an error page — and the label is what makes it
     * defensible.
     */
    @Test
    void servesTheLastGoodAnswerWhileTheUpstreamIsDownAndSaysThatItDid() {
        stubLedgerAndPrices();
        ResponseEntity<String> live = performance();
        assertThat(live.getBody()).contains("\"stale\":false");

        upstream.resetAll();
        upstream.stubFor(get(urlPathEqualTo("/api/v1/transactions"))
                .willReturn(aResponse().withStatus(503)));

        ResponseEntity<String> degraded = performance();

        assertThat(degraded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(degraded.getBody())
                .contains("\"stale\":true")
                .contains("\"totalReturn\":-0.010000");
        // A stale figure must not be cached again further downstream.
        assertThat(degraded.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void anEmptyLedgerIs404NotAnEmptyReport() {
        upstream.stubFor(get(urlPathEqualTo("/api/v1/transactions")).willReturn(okJson("[]")));

        ResponseEntity<String> response = performance();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).contains("\"title\":\"Portfolio not found\"");
    }

    /**
     * A portfolio the upstream knows about but cannot support statistics for is
     * the caller's problem to understand, not a server fault.
     */
    @Test
    void aSingleValuationPointIs422() {
        upstream.stubFor(get(urlPathEqualTo("/api/v1/transactions"))
                .willReturn(okJson(UpstreamStubs.ledger(portfolio))));
        upstream.stubFor(get(urlPathEqualTo("/api/v1/prices"))
                .willReturn(okJson("[" + UpstreamStubs.price("ACME", "2026-01-05", "100.00000000") + "]")));

        ResponseEntity<String> response = performance();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).contains("insufficient-data");
    }

    @Test
    void theRiskFreeRateReachesTheSharpeRatioAndIsEchoedBack() {
        stubLedgerAndPrices();

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/portfolios/{id}/performance?from=2026-01-05&to=2026-01-07&riskFreeRate=0.03",
                String.class, portfolio);

        assertThat(response.getBody()).contains("\"riskFreeRate\":0.03");
    }

    private void stubLedgerAndPrices() {
        upstream.stubFor(get(urlPathEqualTo("/api/v1/transactions"))
                .willReturn(okJson(UpstreamStubs.ledger(portfolio))));
        upstream.stubFor(get(urlPathEqualTo("/api/v1/prices"))
                .willReturn(okJson(UpstreamStubs.prices())));
    }

    private ResponseEntity<String> performance() {
        return restTemplate.getForEntity(
                "/api/v1/portfolios/{id}/performance?from=2026-01-05&to=2026-01-07", String.class, portfolio);
    }
}
