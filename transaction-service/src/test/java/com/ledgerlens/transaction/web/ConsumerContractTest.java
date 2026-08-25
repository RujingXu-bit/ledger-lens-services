package com.ledgerlens.transaction.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerlens.transaction.PostgresTestcontainerConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;

/**
 * Verifies this service against what analytics-service says it depends on.
 *
 * <p>The direction is the point. analytics-service already has tests proving it
 * copes with what it receives, but those run in the consumer's build — by the
 * time they fail, the producer has already shipped. This runs here, so removing
 * a field or renaming an enum constant that another service reads fails
 * <em>this</em> build, before the change is deployed.
 *
 * <p>The contract lists only what the consumer actually reads. Everything else
 * in this API remains free to change without asking anybody, which is what
 * makes the guarantee affordable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainerConfiguration.class)
class ConsumerContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private TestRestTemplate restTemplate;

    @TestFactory
    List<DynamicTest> satisfiesEveryExpectationAnalyticsServiceDeclares() throws IOException {
        JsonNode contract = MAPPER.readTree(Files.readString(contractFile()));
        JsonNode spec = restTemplate.getForObject("/v3/api-docs", JsonNode.class);
        assertThat(spec).as("published OpenAPI description").isNotNull();

        String consumer = contract.get("consumer").asText();
        List<DynamicTest> checks = new ArrayList<>();

        for (JsonNode interaction : contract.get("interactions")) {
            String name = interaction.get("name").asText();
            checks.add(DynamicTest.dynamicTest(
                    "%s needs to %s".formatted(consumer, name),
                    () -> verify(spec, interaction)));
        }
        return checks;
    }

    private void verify(JsonNode spec, JsonNode interaction) {
        String path = interaction.get("path").asText();
        String method = interaction.get("method").asText();

        JsonNode operation = spec.path("paths").path(path).path(method);
        assertThat(operation.isMissingNode())
                .as("%s %s must still exist", method.toUpperCase(), path)
                .isFalse();

        verifyQueryParameters(operation, interaction, path);

        JsonNode schema = resolveResponseSchema(spec, operation, interaction.get("responseStatus").asText());
        verifyFields(schema, interaction, path);
        verifyEnumValues(schema, interaction, path);
    }

    private void verifyQueryParameters(JsonNode operation, JsonNode interaction, String path) {
        List<String> declared = new ArrayList<>();
        operation.path("parameters").forEach(parameter -> declared.add(parameter.path("name").asText()));

        for (JsonNode required : interaction.path("requiredQueryParameters")) {
            assertThat(declared)
                    .as("%s must still accept the query parameter '%s'", path, required.asText())
                    .contains(required.asText());
        }
    }

    /** Unwraps the array wrapper and follows the {@code $ref} into components. */
    private JsonNode resolveResponseSchema(JsonNode spec, JsonNode operation, String status) {
        JsonNode schema = operation.path("responses").path(status)
                .path("content").path("application/json").path("schema");
        assertThat(schema.isMissingNode()).as("a %s response with a JSON body", status).isFalse();

        if ("array".equals(schema.path("type").asText())) {
            schema = schema.path("items");
        }
        String ref = schema.path("$ref").asText(null);
        if (ref != null) {
            String componentName = ref.substring(ref.lastIndexOf('/') + 1);
            schema = spec.path("components").path("schemas").path(componentName);
        }
        return schema;
    }

    private void verifyFields(JsonNode schema, JsonNode interaction, String path) {
        JsonNode properties = schema.path("properties");

        interaction.path("requiredFields").properties().forEach(expected -> {
            String field = expected.getKey();
            String expectedType = expected.getValue().asText();

            assertThat(properties.has(field))
                    .as("%s response must still carry '%s'", path, field)
                    .isTrue();
            assertThat(properties.path(field).path("type").asText())
                    .as("%s response field '%s' must still be a %s", path, field, expectedType)
                    .isEqualTo(expectedType);
        });
    }

    /**
     * Renaming or dropping an enum constant is the change most likely to slip
     * through review: it compiles, the field is still there, and the consumer
     * only discovers it when a real payload arrives with a value it cannot map.
     */
    private void verifyEnumValues(JsonNode schema, JsonNode interaction, String path) {
        interaction.path("requiredEnumValues").properties().forEach(expected -> {
            String field = expected.getKey();
            List<String> published = new ArrayList<>();
            schema.path("properties").path(field).path("enum").forEach(value -> published.add(value.asText()));

            expected.getValue().forEach(required -> assertThat(published)
                    .as("%s field '%s' must still accept the value '%s'", path, field, required.asText())
                    .contains(required.asText()));
        });
    }

    /** The contract lives above both modules, so it is found relative to this one. */
    private static Path contractFile() {
        Path fromModule = Path.of("..", "docs", "contracts", "analytics-service-expects.json");
        return Files.exists(fromModule)
                ? fromModule
                : Path.of("docs", "contracts", "analytics-service-expects.json");
    }
}
