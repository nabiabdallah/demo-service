package com.example.demo.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Metrics and traces (claude-factory ADR 0014): Prometheus on the management port only, traces with the
 * caller's W3C trace id, no raw request paths (they carry identifiers) in metrics or spans, and the trace id
 * in the log line.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.tracing.sampling.probability=1.0")
@AutoConfigureMetrics
@AutoConfigureTracing
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityTest {

    static final String SECRET_ID = "CUST-000123456";
    static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    static final String TRACEPARENT = "00-" + TRACE_ID + "-00f067aa0ba902b7-01";

    @TestConfiguration
    static class Probe {
        @Bean
        ProbeController probeController() {
            return new ProbeController();
        }

        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    @RestController
    static class ProbeController {
        private static final Logger log = LoggerFactory.getLogger(ProbeController.class);

        @GetMapping("/probe/{id}")
        String probe(@PathVariable String id) {
            log.info("probe handled");
            return "ok";
        }
    }

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    @Autowired
    InMemorySpanExporter spans;

    @Autowired
    SdkTracerProvider tracerProvider;

    @Autowired
    ApplicationContext context;

    final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void clearSpans() {
        spans.reset();
    }

    @Test
    void prometheus_metrics_are_served_on_the_management_port_with_uri_templates_only() throws Exception {
        assertThat(get(port, "/probe/" + SECRET_ID, null).statusCode()).isEqualTo(200);

        var metrics = get(managementPort, "/actuator/prometheus", null);

        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body())
                .contains("http_server_requests_seconds_count{")
                .contains("uri=\"/probe/{id}\"")
                .contains("application=\"demo-service\"")
                .contains("http_server_requests_seconds_bucket{")
                .doesNotContain(SECRET_ID);
    }

    @Test
    void the_app_port_serves_no_actuator_endpoints() throws Exception {
        for (String path : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/prometheus")) {
            assertThat(get(port, path, null).statusCode()).as(path).isEqualTo(404);
        }
    }

    @Test
    void a_request_continues_the_callers_trace_without_raw_paths_and_logs_its_trace_id(CapturedOutput output)
            throws Exception {
        assertThat(get(port, "/probe/" + SECRET_ID, TRACEPARENT).statusCode()).isEqualTo(200);
        // The server span ends just after the response is written, so wait for it rather than read at once.
        List<SpanData> server = await().atMost(Duration.ofSeconds(10))
                .until(
                        () -> {
                            tracerProvider.forceFlush().join(1, TimeUnit.SECONDS);
                            return spans.getFinishedSpanItems().stream()
                                    .filter(s -> s.getTraceId().equals(TRACE_ID))
                                    .toList();
                        },
                        found -> !found.isEmpty());
        assertThat(server).allSatisfy(span -> {
            assertThat(span.getName()).doesNotContain(SECRET_ID);
            assertThat(span.getAttributes().asMap().values())
                    .as("span attributes")
                    .noneMatch(value -> String.valueOf(value).contains(SECRET_ID));
        });
        assertThat(output.getAll()).containsPattern("probe handled.*" + TRACE_ID + "|" + TRACE_ID + ".*probe handled");
    }

    @Test
    void metrics_go_to_prometheus_only_and_traces_export_nowhere_until_an_endpoint_is_set() {
        assertThat(context.getBeanNamesForType(io.micrometer.registry.otlp.OtlpMeterRegistry.class))
                .isEmpty();
        assertThat(context.getBeanNamesForType(io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter.class))
                .isEmpty();
    }

    /** Credentials for API calls on the app port. /java-pipeline:add-resource-server makes this a token. */
    static String apiAuthorization() {
        return null; // no API authentication yet
    }

    private HttpResponse<String> get(int onPort, String path, String traceparent) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + onPort + path));
        if (traceparent != null) {
            request.header("traceparent", traceparent);
        }
        if (onPort == port && apiAuthorization() != null) {
            request.header("Authorization", apiAuthorization());
        }
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
