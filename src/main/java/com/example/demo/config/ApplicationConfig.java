package com.example.demo.config;

import org.springframework.context.annotation.Configuration;

/**
 * The only place that knows which adapter implements which port. Declare a {@code @Bean} per use case
 * and per outbound adapter here; application and domain classes stay free of Spring annotations.
 */
@Configuration
public class ApplicationConfig {}
