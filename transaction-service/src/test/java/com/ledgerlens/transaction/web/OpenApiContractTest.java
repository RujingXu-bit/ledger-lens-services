package com.ledgerlens.transaction.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerlens.transaction.PostgresTestcontainerConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;

/**
 * The generated OpenAPI description, asserted as a contract.
 *
 * <p>Generating a document from the code guarantees it matches the code — it
 * does not guarantee the code still matches what was promised to consumers.
 * These assertions are the promises: deleting an endpoint, adding a mutating
 * verb to the ledger, or dropping a documented failure mode fails the build
 * rather than quietly breaking whoever depended on it.
 *
 * <p>Only structural facts are asserted, never wording. A test that pins prose
 * gets deleted the first time it costs somebody a rephrase.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainerConfiguration.class)
class OpenApiContractTest {

    @Autowired
    private TestRestTemplate restTemplate;

    private JsonNode spec() {
        JsonNode spec = restTemplate.getForObject("/v3/api-docs", JsonNode.class);
        assertThat(spec).isNotNull();
        return spec;
    }

    @Test
    void publishesEveryEndpointConsumersDependOn() {
        JsonNode paths = spec().get("paths");

        assertThat(paths.has("/api/v1/transactions")).isTrue();
        assertThat(paths.has("/api/v1/transactions/{id}")).isTrue();
        assertThat(paths.has("/api/v1/prices")).isTrue();
        assertThat(paths.has("/api/v1/portfolios/{portfolioId}/holdings")).isTrue();
    }

    /**
     * The append-only design, stated as something the build can check. If a
     * future edit adds an update or a delete to the ledger, this is what says
     * no.
     */
    @Test
    void theLedgerExposesNoWayToEditOrDeleteAnEntry() {
        JsonNode paths = spec().get("paths");

        assertThat(paths.get("/api/v1/transactions").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("post", "get");
        assertThat(paths.get("/api/v1/transactions/{id}").fieldNames()).toIterable()
                .containsExactly("get");
    }

    /** Prices are reference data, so their write is the idempotent verb. */
    @Test
    void pricesAreWrittenWithPutRatherThanPost() {
        JsonNode prices = spec().get("paths").get("/api/v1/prices");

        assertThat(prices.has("put")).isTrue();
        assertThat(prices.has("post")).isFalse();
    }

    @Test
    void documentsTheFourHundredVersusFourTwentyTwoDistinction() {
        JsonNode responses = spec().get("paths").get("/api/v1/transactions").get("post").get("responses");

        assertThat(responses.has("201")).isTrue();
        assertThat(responses.has("400")).isTrue();
        assertThat(responses.has("422")).isTrue();
        assertThat(responses.get("422").get("content").has("application/problem+json")).isTrue();
    }

    @Test
    void describesTheErrorModelAsATypeAConsumerCanGenerateAClientFrom() {
        assertThat(spec().get("components").get("schemas").has("ProblemDetail")).isTrue();
    }

    @Test
    void keepsOperationalEndpointsOutOfThePublishedApi() {
        assertThat(spec().get("paths").toString()).doesNotContain("/actuator");
    }
}
