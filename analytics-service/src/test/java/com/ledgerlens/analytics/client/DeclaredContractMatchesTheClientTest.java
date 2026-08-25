package com.ledgerlens.analytics.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The consumer's half of the contract: what this service <em>says</em> it needs
 * has to match what it actually reads.
 *
 * <p>Without this, the declaration rots in two directions and both are silent.
 * Over-declare, and transaction-service is held to promises nobody depends on,
 * so a harmless change gets blocked. Under-declare, and the producer's build
 * goes green while removing a field this client parses — which is the failure
 * the whole exercise exists to prevent.
 *
 * <p>No Spring context: this is reflection over two records and a JSON file.
 */
class DeclaredContractMatchesTheClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Which client record carries the response of which declared interaction. */
    private static final Map<String, String> DTO_FOR_PATH = Map.of(
            "/api/v1/transactions", "TransactionDto",
            "/api/v1/prices", "PriceDto");

    @Test
    void everyFieldDeclaredInTheContractIsReallyReadByTheClient() throws IOException {
        forEachInteraction((path, declaredFields) -> {
            List<String> parsed = componentsOf(DTO_FOR_PATH.get(path));
            assertThat(parsed)
                    .as("%s: the contract claims these fields are needed, so the client must parse them", path)
                    .containsAll(declaredFields);
        });
    }

    @Test
    void theClientReadsNothingItHasNotDeclared() throws IOException {
        forEachInteraction((path, declaredFields) -> {
            List<String> parsed = componentsOf(DTO_FOR_PATH.get(path));
            assertThat(declaredFields)
                    .as("%s: the client parses these, so the contract must ask for them — otherwise "
                            + "transaction-service can remove one and its build stays green", path)
                    .containsAll(parsed);
        });
    }

    private void forEachInteraction(java.util.function.BiConsumer<String, List<String>> check) throws IOException {
        JsonNode contract = MAPPER.readTree(Files.readString(contractFile()));
        assertThat(contract.get("consumer").asText()).isEqualTo("analytics-service");

        for (JsonNode interaction : contract.get("interactions")) {
            String path = interaction.get("path").asText();
            assertThat(DTO_FOR_PATH).as("every declared interaction maps to a client record").containsKey(path);

            List<String> declared = new ArrayList<>();
            interaction.get("requiredFields").fieldNames().forEachRemaining(declared::add);
            check.accept(path, declared);
        }
    }

    private static List<String> componentsOf(String recordName) {
        for (Class<?> nested : TransactionServiceClient.class.getDeclaredClasses()) {
            if (nested.getSimpleName().equals(recordName)) {
                return java.util.Arrays.stream(nested.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList();
            }
        }
        throw new AssertionError("no record named " + recordName + " in TransactionServiceClient");
    }

    private static Path contractFile() {
        Path fromModule = Path.of("..", "docs", "contracts", "analytics-service-expects.json");
        return Files.exists(fromModule)
                ? fromModule
                : Path.of("docs", "contracts", "analytics-service-expects.json");
    }
}
