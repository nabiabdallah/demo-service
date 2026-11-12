package com.example.demo.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;

/**
 * Actuator lives on the management port (claude-factory ADR 0014): Kubernetes probes and Prometheus only;
 * nothing else is reachable, and health shows no internals.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ActuatorEndpointsTest {

    @LocalManagementPort
    int managementPort;

    final HttpClient http = HttpClient.newHttpClient();

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health/liveness", "/actuator/health/readiness"})
    void kubernetes_probes_report_up(String probe) throws Exception {
        var response = get(probe, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void health_shows_status_only_without_components_or_details() throws Exception {
        var response = get("/actuator/health", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"status\":\"UP\"")
                .doesNotContain("components")
                .doesNotContain("details");
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/beans", "/actuator/metrics", "/actuator/configprops"})
    void other_actuator_endpoints_are_not_exposed(String endpoint) throws Exception {
        // Exactly 404. /java-pipeline:add-resource-server sends a token here and adds a tokenless 401 case.
        var response = get(endpoint, null);

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain("propertySources");
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + managementPort + path));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
