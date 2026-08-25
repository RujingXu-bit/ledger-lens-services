package com.ledgerlens.analytics.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

/**
 * The promises analytics-service makes about its own API.
 *
 * <p>The interesting ones here are about degradation. A consumer that cannot
 * see {@code stale} in the schema has no way to know a figure might be cached,
 * and a consumer that does not know {@code 503} is possible will treat an
 * upstream outage as a bug in its own code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiContractTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private JsonNode spec() {
        JsonNode spec = restTemplate.getForObject("/v3/api-docs", JsonNode.class);
        assertThat(spec).isNotNull();
        return spec;
    }

    @Test
    void publishesTheOneEndpointItExistsFor() {
        assertThat(spec().get("paths").has("/api/v1/portfolios/{portfolioId}/performance")).isTrue();
    }

    /** Measuring performance must never be a mutation. */
    @Test
    void performanceIsReadOnly() {
        assertThat(spec().get("paths").get("/api/v1/portfolios/{portfolioId}/performance").fieldNames())
                .toIterable().containsExactly("get");
    }

    @Test
    void documentsThatItsUpstreamCanBeUnavailable() {
        JsonNode responses = spec().get("paths")
                .get("/api/v1/portfolios/{portfolioId}/performance").get("get").get("responses");

        assertThat(responses.has("200")).isTrue();
        assertThat(responses.has("404")).isTrue();
        assertThat(responses.has("422")).isTrue();
        // The one a naive client will otherwise mistake for its own bug.
        assertThat(responses.has("503")).isTrue();
    }

    /**
     * Degradation has to be visible in the schema, not just in the prose. A
     * generated client that has no {@code stale} field cannot check it.
     */
    @Test
    void thePayloadTellsAConsumerWhetherTheFiguresAreLiveAndHowTheyWereMeasured() {
        JsonNode properties = spec().get("components").get("schemas")
                .get("PerformanceResponse").get("properties");

        assertThat(properties.has("stale")).isTrue();
        assertThat(properties.has("computedAt")).isTrue();
        assertThat(properties.has("returnMethod")).isTrue();
        assertThat(properties.has("riskFreeRate")).isTrue();
    }

    @Test
    void keepsOperationalEndpointsOutOfThePublishedApi() {
        assertThat(spec().get("paths").toString()).doesNotContain("/actuator");
    }
}
