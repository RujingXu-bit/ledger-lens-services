package com.ledgerlens.analytics;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnalyticsClockConfiguration {

    /**
     * The clock is a bean so that "now" is an injected dependency rather than a
     * static call. Cache staleness and the default end of the reporting window
     * both depend on it, and neither is testable if the code reaches for
     * {@code Instant.now()} directly.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
