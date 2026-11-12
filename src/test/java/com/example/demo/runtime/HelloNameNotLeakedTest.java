package com.example.demo.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

/** AC9: the name never reaches logs, metric tags or span attributes (200 and 400 paths). */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.tracing.sampling.probability=1.0")
@AutoConfigureMetrics
@AutoConfigureTracing
@ExtendWith(OutputCaptureExtension.class)
class HelloNameNotLeakedTest {

    static final String SENTINEL = "SentinelZq7Name";

    @TestConfiguration
    static class Spans {
        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    InMemorySpanExporter spans;

    @Autowired
    SdkTracerProvider tracerProvider;

    @Autowired
    MeterRegistry meters;

    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clearSpans() {
        spans.reset();
    }

    @Test
    void name_is_absent_from_logs_metrics_and_spans_on_the_200_path(CapturedOutput output) throws Exception {
        var response = get("/hello?name=" + SENTINEL);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains(SENTINEL); // sanity: the request really carried it
        assertNothingLeaked(output, 200);
    }

    @Test
    void name_is_absent_from_logs_metrics_and_spans_on_the_400_path(CapturedOutput output) throws Exception {
        var response = get("/hello?name=" + SENTINEL + "%0A");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).doesNotContain(SENTINEL);
        assertNothingLeaked(output, 400);
    }

    @Test
    void name_is_absent_from_logs_metrics_and_spans_when_too_long(CapturedOutput output) throws Exception {
        var response = get("/hello?name=" + SENTINEL + "x".repeat(60));

        assertThat(response.statusCode()).isEqualTo(400);
        assertNothingLeaked(output, 400);
    }

    private void assertNothingLeaked(CapturedOutput output, int status) {
        // wait until the server span for /hello has ended, then inspect everything
        List<SpanData> finished = await().atMost(Duration.ofSeconds(10))
                .until(
                        () -> {
                            tracerProvider.forceFlush().join(1, TimeUnit.SECONDS);
                            return spans.getFinishedSpanItems().stream()
                                    .filter(s ->
                                            String.valueOf(s.getAttributes()).contains("/hello")
                                                    || s.getName().contains("hello"))
                                    .toList();
                        },
                        found -> !found.isEmpty());
        assertThat(finished).allSatisfy(span -> {
            assertThat(span.getName()).doesNotContain(SENTINEL);
            assertThat(span.getAttributes().asMap().entrySet())
                    .as("span attributes (incl. http.url)")
                    .noneMatch(e -> String.valueOf(e.getKey()).contains(SENTINEL)
                            || String.valueOf(e.getValue()).contains(SENTINEL));
        });

        var timers = meters.find("http.server.requests").tag("uri", "/hello").timers();
        assertThat(timers)
                .as("http.server.requests for uri=/hello status " + status)
                .isNotEmpty();
        assertThat(timers)
                .allSatisfy(t -> assertThat(t.getId().getTags())
                        .noneMatch(tag -> tag.getKey().contains(SENTINEL)
                                || tag.getValue().contains(SENTINEL)));
        assertThat(meters.getMeters())
                .allSatisfy(m -> assertThat(m.getId().getTags())
                        .noneMatch(tag -> tag.getValue().contains(SENTINEL)));

        assertThat(output.getAll()).doesNotContain(SENTINEL);
    }

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + pathAndQuery))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
