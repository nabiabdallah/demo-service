package com.example.demo.config;

import com.example.demo.application.GreetService;
import com.example.demo.application.port.in.GreetUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The only place that knows which adapter implements which port. Declare a {@code @Bean} per use case
 * and per outbound adapter here; application and domain classes stay free of Spring annotations.
 */
@Configuration
public class ApplicationConfig {

    @Bean
    GreetUseCase greetUseCase() {
        return new GreetService();
    }
}
