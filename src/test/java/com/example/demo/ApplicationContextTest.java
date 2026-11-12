package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/** Boots the full Spring context: catches wiring mistakes in config/ before they reach a deployment. */
@SpringBootTest
class ApplicationContextTest {

    @Test
    void context_starts() {}
}
