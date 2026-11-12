package com.example.demo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.demo.application.GreetService;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class HelloControllerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new HelloController(new GreetService()))
            .setControllerAdvice(new ErrorHandler())
            .build();

    private MvcResult call(String name) throws Exception {
        return mvc.perform(get("/hello").param("name", name)).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static void assertGreeting(MvcResult r, String expectedGreeting) throws Exception {
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(MediaType.parseMediaType(r.getResponse().getContentType())
                        .isCompatibleWith(MediaType.APPLICATION_JSON))
                .isTrue();
        assertThat((String) JsonPath.read(body(r), "$.greeting")).isEqualTo(expectedGreeting);
    }

    private static void assertProblem(MvcResult r, int status, String... mustNotAppear) throws Exception {
        assertThat(r.getResponse().getStatus()).isEqualTo(status);
        assertThat(r.getResponse().getContentType()).startsWith("application/problem+json");
        String body = body(r);
        assertThat((String) JsonPath.read(body, "$.instance")).startsWith("urn:uuid:");
        assertThat(body).doesNotContain("trace", "exception", "stackTrace", "at com.example");
        for (String s : mustNotAppear) {
            assertThat(body).doesNotContain(s);
        }
    }

    @Test
    void greets_alice() throws Exception { // AC1
        var r = call("Alice");
        assertGreeting(r, "Hello, Alice!");
        assertThat(body(r)).isEqualTo("{\"greeting\":\"Hello, Alice!\"}");
    }

    @Test
    void surrounding_whitespace_is_trimmed() throws Exception { // AC2
        assertGreeting(call("  Alice  "), "Hello, Alice!");
    }

    @Test
    void missing_name_is_400_problem() throws Exception { // AC3
        var r = mvc.perform(get("/hello")).andReturn();
        assertProblem(r, 400);
    }

    @Test
    void empty_name_is_400_problem() throws Exception { // AC4
        assertProblem(call(""), 400);
    }

    @Test
    void whitespace_only_name_is_400_problem() throws Exception { // AC4
        assertProblem(call("   "), 400);
    }

    @Test
    void name_of_51_characters_is_400_and_not_echoed() throws Exception { // AC5
        String name = "n".repeat(51);
        assertProblem(call(name), 400, name);
    }

    @Test
    void name_of_exactly_50_characters_is_accepted() throws Exception { // AC5 boundary
        String name = "n".repeat(50);
        assertGreeting(call(name), "Hello, " + name + "!");
    }

    @Test
    void length_is_counted_after_trimming() throws Exception { // AC2/AC5
        String name = "n".repeat(50);
        assertGreeting(call("  " + name + "  "), "Hello, " + name + "!");
    }

    @Test
    void fifty_emoji_name_is_accepted_because_length_counts_code_points() throws Exception { // AC5
        String name = "😀".repeat(50);
        assertThat(name.length()).isEqualTo(100);
        assertGreeting(call(name), "Hello, " + name + "!");
    }

    @Test
    void fifty_one_emoji_name_is_rejected() throws Exception { // AC5
        String name = "😀".repeat(51);
        assertProblem(call(name), 400, name);
    }

    @Test
    void non_ascii_names_are_returned_unchanged_as_utf_8() throws Exception { // AC6
        for (String name : List.of("Zoë", "李雷")) {
            var r = call(name);
            assertGreeting(r, "Hello, " + name + "!");
            // raw bytes must be the UTF-8 encoding, not a Latin-1 or escaped form
            assertThat(r.getResponse().getContentAsByteArray()).contains(name.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void markup_is_returned_only_as_a_json_string_value() throws Exception { // AC7
        var r = call("<b>x</b>");
        assertGreeting(r, "Hello, <b>x</b>!");
        assertThat(r.getResponse().getContentType()).startsWith("application/json");
    }

    @Test
    void post_is_405_problem() throws Exception { // AC8
        var r = mvc.perform(post("/hello").param("name", "Alice")).andReturn();
        assertProblem(r, 405, "Alice");
    }

    @Test
    void control_characters_are_rejected_without_echoing_the_name() throws Exception { // AC12
        for (String name : List.of("a\nb", "a\u0000b", "Alice\n", "a\tb", "a\u007fb", "a\u0085b")) {
            assertProblem(call(name), 400, "Alice");
        }
    }

    @Test
    void trailing_newline_is_rejected_not_trimmed_away() throws Exception { // AC12 edge: name=Alice%0A
        var r = mvc.perform(get(java.net.URI.create("/hello?name=Alice%0A"))).andReturn();
        assertProblem(r, 400, "Alice");
    }

    @Test
    void percent_encoded_nul_is_rejected() throws Exception { // AC12
        var r = mvc.perform(get(java.net.URI.create("/hello?name=a%00b"))).andReturn();
        assertProblem(r, 400);
    }

    @Test
    void concurrent_requests_each_get_only_their_own_name() throws Exception { // AC10
        int n = 12;
        var barrier = new CyclicBarrier(n);
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                String name = "caller" + i + "x";
                results.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return body(call(name));
                }));
            }
            for (int i = 0; i < n; i++) {
                assertThat(results.get(i).get(30, TimeUnit.SECONDS))
                        .isEqualTo("{\"greeting\":\"Hello, caller" + i + "x!\"}");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
