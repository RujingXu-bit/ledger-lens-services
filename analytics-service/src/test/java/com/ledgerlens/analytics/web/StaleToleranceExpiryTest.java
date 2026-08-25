package com.ledgerlens.analytics.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
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
 * The other half of the fallback: it has to stop.
 *
 * <p>Serving cached figures forever would be worse than failing. A stale price
 * from an hour ago, presented as a portfolio's performance, is a number the
 * caller cannot tell is wrong. Beyond the tolerance the honest answer is 503.
 *
 * <p>A separate class purely so the tolerance can be set to effectively zero
 * without affecting the other tests — cheaper and clearer than mocking the
 * clock for one assertion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "ledgerlens.transaction-service.stale-tolerance=1ms")
class StaleToleranceExpiryTest {

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

    @Test
    void aCachedResultPastItsToleranceIsNotServedAtAll() {
        UUID portfolio = UUID.randomUUID();
        upstream.stubFor(get(urlPathEqualTo("/api/v1/transactions"))
                .willReturn(okJson(UpstreamStubs.ledger(portfolio))));
        upstream.stubFor(get(urlPathEqualTo("/api/v1/prices"))
                .willReturn(okJson(UpstreamStubs.prices())));

        assertThat(performance(portfolio).getStatusCode()).isEqualTo(HttpStatus.OK);

        upstream.resetAll();
        upstream.stubFor(get(urlPathEqualTo("/api/v1/transactions"))
                .willReturn(aResponse().withStatus(503)));

        ResponseEntity<String> response = performance(portfolio);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("no recent result was available");
    }

    private ResponseEntity<String> performance(UUID portfolio) {
        return restTemplate.getForEntity(
                "/api/v1/portfolios/{id}/performance?from=2026-01-05&to=2026-01-07", String.class, portfolio);
    }
}
