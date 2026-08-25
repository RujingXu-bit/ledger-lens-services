package com.ledgerlens.transaction.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The description of this service's API, generated from the code rather than
 * maintained beside it.
 *
 * <p>A hand-written API document is wrong the first time somebody changes a
 * controller and forgets it. Generating from the annotations means the
 * description can only be wrong if the code is.
 *
 * <p>What is written here is the part no annotation can infer: what the service
 * is <em>for</em>, and the conventions a consumer has to know before the
 * endpoint list means anything.
 */
@Configuration
public class OpenApiConfiguration {

    @Bean
    OpenAPI transactionServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Ledger Lens — transaction-service")
                        .version("v1")
                        .description("""
                                The system of record for portfolio transactions, holdings and closing prices.
                                It owns the only database in the system; every other service asks it over HTTP.

                                **Conventions a consumer needs before reading the endpoints:**

                                * **The ledger is append-only.** There is no `PUT` and no `DELETE` on a
                                  transaction. A mistaken entry is corrected by recording a reversing
                                  transaction, which is why any figure derived from the ledger can be
                                  recomputed from scratch at any time.
                                * **Money is decimal, not floating point.** Cash carries four decimal
                                  places, quantities eight, and values are serialised in plain notation
                                  (`1234.5600`, never `1.2345E+3`). Parse them as decimals; a JSON parser
                                  that converts numbers to IEEE doubles will silently lose cents.
                                * **`cashAmount` is derived by the server** from quantity, price and fee, and
                                  is signed — negative when money leaves the portfolio. It is never accepted
                                  from a client.
                                * **Prices are reference data and are upserted.** `PUT /api/v1/prices` is
                                  idempotent by design: loading the same trading day twice leaves one row,
                                  and a restated price overwrites the previous one.
                                * **Holdings are derived, not stored.** There is no holdings table; a
                                  position is a fold over the transaction log, and `?asOf=` answers it as
                                  at any past instant.
                                * **Errors are RFC 9457 `application/problem+json`.** `400` means the shape
                                  of the payload is wrong; `422` means it was well-formed but described a
                                  transaction that cannot exist. Branch on `status` and `type`, never on
                                  the prose in `detail`.

                                Timestamps are ISO-8601 instants in UTC. Trading dates are ISO calendar
                                dates, because a closing price belongs to a day rather than to a moment.
                                """)
                        .contact(new Contact().name("Ledger Lens").url("https://github.com/"))
                        .license(new License().name("MIT")))
                .tags(List.of(
                        new Tag().name("Transactions").description("The append-only ledger"),
                        new Tag().name("Holdings").description("Positions, derived from the ledger on request"),
                        new Tag().name("Prices").description("Closing prices — reference data, upserted")))
                .components(new Components().addSchemas("ProblemDetail", problemDetailSchema()));
    }

    /**
     * RFC 9457 spelled out, so that a consumer generating a client gets a typed
     * error model rather than a free-form object.
     */
    private static Schema<?> problemDetailSchema() {
        return new Schema<>()
                .type("object")
                .description("RFC 9457 problem detail. `errors` is present only on validation failures.")
                .addProperty("type", new StringSchema().format("uri")
                        .description("Stable identifier for the kind of problem; safe to branch on."))
                .addProperty("title", new StringSchema().description("Short, human-readable summary."))
                .addProperty("status", new Schema<Integer>().type("integer").format("int32"))
                .addProperty("detail", new StringSchema().description("Explanation for this occurrence. Prose — do not parse."))
                .addProperty("instance", new StringSchema().description("The request path that produced it."))
                .addProperty("errors", new Schema<>().type("object")
                        .description("Field name to message, one entry per violated constraint.")
                        .additionalProperties(new StringSchema()));
    }
}
