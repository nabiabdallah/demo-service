package com.example.demo.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

/** Logs are ECS JSON by default (for log collectors) and plain text with the local profile. */
class StructuredLoggingTest {

    private static String lineContaining(CapturedOutput output, String marker) {
        return output.getOut()
                .lines()
                .filter(line -> line.contains(marker))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no log line containing " + marker));
    }

    @Nested
    @SpringBootTest
    @ExtendWith(OutputCaptureExtension.class)
    class ByDefault {
        @Autowired
        ConfigurableEnvironment environment;

        /**
         * Spring Boot configures logging once per JVM (it marks the logging library as initialized), so which
         * test context configured it first would decide this test's outcome. Re-run Boot's own logging
         * initialization from this context's environment, as application startup does, to make the test
         * independent of test order.
         */
        // Note: this re-init applies the structured format but not logging.level.* or log files the way startup
        // does; fine while the service sets none. Later tests keep this (default-profile) configuration.
        @BeforeEach
        void applyThisContextsLoggingConfiguration() {
            var loggingSystem = LoggingSystem.get(getClass().getClassLoader());
            loggingSystem.cleanUp();
            loggingSystem.beforeInitialize();
            loggingSystem.initialize(new LoggingInitializationContext(environment), null, null);
        }

        @Test
        void log_lines_are_ecs_json(CapturedOutput output) {
            LoggerFactory.getLogger(StructuredLoggingTest.class).info("structured-logging-probe");

            var line = lineContaining(output, "structured-logging-probe");
            assertThat(line).startsWith("{");
            assertThat(JsonPath.<String>read(line, "$.message")).isEqualTo("structured-logging-probe");
            assertThat(JsonPath.<String>read(line, "$.log.level")).isEqualTo("INFO");
            assertThat(JsonPath.<String>read(line, "$['@timestamp']")).isNotBlank();
        }
    }

    /**
     * Asserts the effective setting, not log output: Spring Boot configures logging once per test JVM, so a
     * later context with another profile keeps the format of the first. (Verified in an isolated run: with
     * this profile the lines are plain text.)
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("local")
    class WithLocalProfile {
        @Autowired
        Environment environment;

        @Test
        void structured_console_logging_is_switched_off() {
            assertThat(environment.getProperty("logging.structured.format.console"))
                    .isEmpty();
        }
    }

    @Nested
    @SpringBootTest
    class Setting {
        @Autowired
        Environment environment;

        @Test
        void structured_console_logging_is_ecs_by_default() {
            assertThat(environment.getProperty("logging.structured.format.console"))
                    .isEqualTo("ecs");
        }
    }
}
