package com.ledgerlens.analytics.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import java.util.UUID;
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
 * Two failures found in the week 1 review, pinned so they cannot come back.
 *
 * <p>The page size is forced down to two here. Testing paging at the real 500
 * would mean fabricating five hundred rows to prove a boundary that behaves
 * identically at two — the size is configurable precisely so this test can stay
 * readable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "ledgerlens.transaction-service.page-size=2")
class LedgerPagingAndRejectionTest {

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

    /**
     * The regression that matters. Before the fix the client asked for one page
     * and used whatever came back, so a portfolio with more transactions than
     * fit was measured on a fragment of its own ledger — and nothing in the
     * response said so.
     *
     * <p>Three transactions across two pages: the deposit and the buy are on
     * page one, the closing valuation depends on the buy being seen at all.
     */
    @Test
    void readsEveryPageOfTheLedgerRatherThanTheFirstOne() {
        UUID portfolio = UUID.randomUUID();

        upstream.stubFor(get(urlPathEqualTo("/api/v1/transactions"))
                .withQueryParam("page", equalTo("0"))
                .willReturn(okJson("["
                        + UpstreamStubs.transaction(portfolio, "DEPOSIT", null, null, "1000.0000", "2026-01-05T09:00:00Z")
                        + "," + UpstreamStubs.transaction(portfolio, "BUY", "ACME", "10.00000000", "-1000.0000", "2026-01-05T09:00:00Z")
                        + "]")));
        upstream.stubFor(get(urlPathEqualTo("/api/v1/transactions"))
                .withQueryParam("page", equalTo("1"))
                .willReturn(okJson("["
                        + UpstreamStubs.transaction(portfolio, "DEPOSIT", null, null, "500.0000", "2026-01-06T09:00:00Z")
                        + "]")));
        upstream.stubFor(get(urlPathEqualTo("/api/v1/prices")).willReturn(okJson(UpstreamStubs.prices())));

        ResponseEntity<String> response = performance(portfolio);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // Page two's 500 deposit lands on the sixth: 1,100 of stock plus 500 cash.
        assertThat(response.getBody()).contains("\"endingValue\":1490.0000");

        upstream.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/transactions")).withQueryParam("page", equalTo("0")));
        upstream.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/transactions")).withQueryParam("page", equalTo("1")));
    }

    /**
     * A short page ends the walk. With the page size at two, a single-row
     * response is short, so nothing should ask for a second page — the walk
     * must not cost one wasted round trip per call.
     */
    @Test
    void stopsAtTheFirstShortPageInsteadOfProbingForOneMore() {
        UUID portfolio = UUID.randomUUID();
        upstream.stubFor(get(urlPathEqualTo("/api/v1/transactions"))
                .withQueryParam("page", equalTo("0"))
                .willReturn(okJson("["
                        + UpstreamStubs.transaction(portfolio, "BUY", "ACME", "10.00000000", "-1000.0000",
                                "2026-01-05T09:00:00Z")
                        + "]")));
        upstream.stubFor(get(urlPathEqualTo("/api/v1/prices")).willReturn(okJson(UpstreamStubs.prices())));

        assertThat(performance(portfolio).getStatusCode()).isEqualTo(HttpStatus.OK);

        upstream.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/transactions")));
    }

    /**
     * A 4xx from upstream used to escape as a 500 with Spring's default error
     * body, breaking the RFC 9457 contract this service publishes.
     */
    @Test
    void aRejectedRequestIs502ProblemJsonRatherThanALeakedStackTrace() {
        upstream.stubFor(get(urlPathEqualTo("/api/v1/transactions"))
                .willReturn(aResponse().withStatus(404).withBody("Not Found")));

        ResponseEntity<String> response = performance(UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody())
                .contains("\"title\":\"Upstream rejected the request\"")
                .contains("upstream-rejected")
                .doesNotContain("404");
    }

    /** A definite refusal is not worth asking twice. */
    @Test
    void aRejectedRequestIsNotRetried() {
        upstream.stubFor(get(urlPathEqualTo("/api/v1/transactions"))
                .willReturn(aResponse().withStatus(400)));

        performance(UUID.randomUUID());

        upstream.verify(1, getRequestedFor(urlPathEqualTo("/api/v1/transactions")));
    }

    private ResponseEntity<String> performance(UUID portfolio) {
        return restTemplate.getForEntity(
                "/api/v1/portfolios/{id}/performance?from=2026-01-05&to=2026-01-07", String.class, portfolio);
    }
}
