package com.ledgerlens.transaction.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.ledgerlens.transaction.PostgresTestcontainerConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;

/**
 * The committed spec in {@code docs/openapi/} must match what this service
 * actually publishes.
 *
 * <p>Day 6 committed the spec so that an API change would arrive as a reviewable
 * diff. Nothing enforced it, so the file could go stale silently — and a stale
 * published contract does the opposite of what it was committed for. This is
 * that enforcement, and it lives in the test suite rather than only in the
 * pipeline so it fails on the laptop that caused it.
 *
 * <p>The comparison can only be exact because the spec is deterministic: the
 * {@code servers} block is declared in configuration rather than inferred from
 * the request that generated it.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PostgresTestcontainerConfiguration.class)
class OpenApiSpecIsCommittedTest {

    private static final String SERVICE = "transaction-service";

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void theCommittedSpecMatchesWhatTheServicePublishes() throws IOException {
        String published = restTemplate.getForObject("/v3/api-docs.yaml", String.class);
        assertThat(published).as("generated OpenAPI document").isNotBlank();

        Path committed = specFile();
        assertThat(Files.exists(committed))
                .as("%s should exist — run scripts/export-openapi.sh", committed)
                .isTrue();

        assertThat(normalise(Files.readString(committed)))
                .as("docs/openapi/%s.yaml is out of date. Run scripts/export-openapi.sh "
                        + "with both services running and commit the result.", SERVICE)
                .isEqualTo(normalise(published));
    }

    /** Only line endings and trailing whitespace; content differences must still fail. */
    private static String normalise(String yaml) {
        return yaml.replace("\r\n", "\n").stripTrailing();
    }

    private static Path specFile() {
        Path fromModule = Path.of("..", "docs", "openapi", SERVICE + ".yaml");
        return Files.exists(fromModule) ? fromModule : Path.of("docs", "openapi", SERVICE + ".yaml");
    }
}
