package com.example.demo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.domain.DomainException;
import com.example.demo.domain.NotFoundException;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.ServletWebRequest;

@ExtendWith(OutputCaptureExtension.class)
class ErrorHandlerTest {

    private static final String SECRET_ID = "CUST-000123456";

    static class ThingNotFoundException extends NotFoundException {
        ThingNotFoundException() {
            super("Thing not found");
        }
    }

    static class RuleViolatedException extends DomainException {
        RuleViolatedException() {
            super("Rule violated");
        }
    }

    @RestController
    static class ProbeController {
        @GetMapping("/probe/{id}/{kind}")
        String probe(@PathVariable String id, @PathVariable String kind) {
            switch (kind) {
                case "missing" -> throw new ThingNotFoundException();
                case "rule" -> throw new RuleViolatedException();
                case "boom" -> throw new IllegalStateException("boom");
                case "own-instance" -> {
                    var problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
                    problem.setInstance(URI.create("urn:example:conflict-1"));
                    throw new ErrorResponseException(HttpStatus.CONFLICT, problem, null);
                }
                default -> {
                    return "ok";
                }
            }
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new ErrorHandler())
            .build();

    @Test
    void not_found_maps_to_404_problem_without_request_path() throws Exception {
        var body = mvc.perform(get("/probe/{id}/missing", SECRET_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Thing not found"))
                .andExpect(jsonPath("$.instance").value(startsWith("urn:uuid:")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(SECRET_ID);
    }

    @Test
    void domain_rule_violation_maps_to_422() throws Exception {
        mvc.perform(get("/probe/{id}/rule", SECRET_ID))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value("Rule violated"));
    }

    @Test
    void spring_mvc_errors_keep_their_4xx_status_and_hide_the_path() throws Exception {
        var body = mvc.perform(post("/probe/{id}/x", SECRET_ID))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.instance").value(startsWith("urn:uuid:")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).doesNotContain(SECRET_ID);
    }

    @Test
    void unexpected_error_is_a_generic_500_logged_with_error_id_and_stack_trace(CapturedOutput output)
            throws Exception {
        var body = mvc.perform(get("/probe/{id}/boom", SECRET_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("Internal error"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String errorId = JsonPath.read(body, "$.errorId");

        assertThat(body).doesNotContain("boom").doesNotContain(SECRET_ID);
        assertThat(output)
                .contains("Unhandled exception errorId=" + errorId)
                .contains("java.lang.IllegalStateException: boom");
    }

    @Test
    void a_problem_that_already_has_an_instance_keeps_it() throws Exception {
        mvc.perform(get("/probe/{id}/own-instance", SECRET_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.instance").value("urn:example:conflict-1"));
    }

    @Test
    void a_body_that_is_not_a_problem_passes_through_unchanged() {
        var request = new ServletWebRequest(new MockHttpServletRequest());

        var response = new ErrorHandler()
                .createResponseEntity("plain body", new HttpHeaders(), HttpStatus.BAD_REQUEST, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo("plain body");
    }
}
