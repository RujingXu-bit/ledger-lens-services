package com.ledgerlens.analytics.client;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(TransactionServiceProperties.class)
public class RestClientConfig {

    /**
     * {@code RestClient} rather than {@code RestTemplate}: same synchronous
     * model, current API, and it is what Spring recommends for new code.
     * {@code WebClient} would bring a reactive stack this service has no use
     * for — the work here is one blocking fetch followed by arithmetic.
     */
    @Bean
    RestClient transactionServiceRestClient(RestClient.Builder builder,
                                            TransactionServiceProperties properties) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());

        return builder
                .baseUrl(properties.baseUrl())
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
    }
}
