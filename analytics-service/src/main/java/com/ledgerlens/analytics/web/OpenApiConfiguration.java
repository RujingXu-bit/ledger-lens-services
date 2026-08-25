package com.ledgerlens.analytics.web;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.servers.Server;
import io.swagger.v3.oas.models.tags.Tag;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {

    /**
     * Where this service actually answers. A property rather than a literal:
     * the previous version hard-coded the Container Apps FQDN in this file, so
     * recreating that environment - which changes its generated suffix - would
     * have left the published contract naming a host that no longer exists,
     * fixable only by editing code and redeploying.
     */
    @Value("${ledgerlens.openapi.public-url}")
    private String publicUrl;

    @Bean
    OpenAPI analyticsServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Ledger Lens — analytics-service")
                        .version("v1")
                        .description("""
                                Portfolio performance measurement. Owns no data: it reads the ledger and the
                                price history from transaction-service and derives everything on request.

                                **What a consumer needs to know before trusting a number here:**

                                * **Returns are time-weighted.** External cash flows are removed before each
                                  day's return is measured, so a contribution is never mistaken for
                                  performance. `returnMethod` states this in every response, because a
                                  time-weighted figure and a money-weighted one are not comparable and
                                  nothing else in the payload would tell you which you had.
                                * **Deposits and withdrawals are external; dividends are not.** A dividend
                                  is return the portfolio earned, so netting it out would erase the
                                  performance it represents.
                                * **Maximum drawdown is measured on the return index, not on portfolio
                                  value.** A withdrawal lowers value without losing anyone a cent.
                                * **`sharpeRatio` is null when volatility is zero** — undefined rather than
                                  infinite — and `riskFreeRate` is echoed back on every response, because a
                                  Sharpe ratio without its risk-free rate is not comparable to anything.
                                * **`stale` may be true.** When transaction-service cannot be reached, the
                                  last successful result is served rather than an error, marked
                                  `"stale": true` with the `computedAt` it was calculated at, and with
                                  `Cache-Control: no-store`. Past the staleness tolerance the request gets
                                  `503` instead. Check the flag before acting on the figures.
                                * **`503` is not `500`.** It means this service is fine but its upstream is
                                  not, so retrying may work. `404` is never used for an outage.

                                Rates are decimal fractions, not percentages: `0.158612` is 15.86%.
                                """)
                        .contact(new Contact().name("Ledger Lens").url("https://github.com/"))
                        .license(new License().name("MIT")))
                .servers(List.of(
                        new Server().url(publicUrl)
                                .description("Azure Container Apps — the only public surface of this system"),
                        new Server().url("http://localhost:8082").description("Local development")))
                .tags(List.of(new Tag().name("Performance")
                        .description("Return, volatility, maximum drawdown and the Sharpe ratio")))
                .components(new Components().addSchemas("ProblemDetail", problemDetailSchema()));
    }

    private static Schema<?> problemDetailSchema() {
        return new Schema<>()
                .type("object")
                .description("RFC 9457 problem detail, the same format transaction-service uses.")
                .addProperty("type", new StringSchema().format("uri")
                        .description("Stable identifier for the kind of problem; safe to branch on."))
                .addProperty("title", new StringSchema())
                .addProperty("status", new Schema<Integer>().type("integer").format("int32"))
                .addProperty("detail", new StringSchema().description("Prose — do not parse."))
                .addProperty("instance", new StringSchema());
    }
}
