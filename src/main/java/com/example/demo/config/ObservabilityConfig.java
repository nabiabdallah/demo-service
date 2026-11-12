package com.example.demo.config;

import io.micrometer.observation.ObservationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Observability wiring (claude-factory ADR 0014). Request paths carry identifiers ({@code /accounts/CUST-1}),
 * so the raw URL never leaves the service: metrics and spans keep the URI template ({@code /accounts/{id}}).
 */
@Configuration
public class ObservabilityConfig {

    /** Micrometer's high-cardinality key for the full request/target URL, copied into span attributes. */
    static final String RAW_URL = "http.url";

    @Bean
    ObservationFilter withoutRawUrls() {
        return context -> {
            context.removeHighCardinalityKeyValue(RAW_URL);
            return context;
        };
    }
}
