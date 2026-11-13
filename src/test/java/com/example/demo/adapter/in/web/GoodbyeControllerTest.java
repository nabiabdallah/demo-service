package com.example.demo.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.example.demo.application.FarewellService;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
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

class GoodbyeControllerTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new GoodbyeController(new FarewellService()))
            .setControllerAdvice(new ErrorHandler())
            .build();

    private MvcResult call(String name) throws Exception {
        return mvc.perform(get("/goodbye").param("name", name)).andReturn();
    }

    private MvcResult callRaw(String uri) throws Exception {
        return mvc.perform(get(URI.create(uri))).andReturn();
    }

    private static String body(MvcResult r) throws Exception {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static void assertMessage(MvcResult r, String expected) throws Exception {
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(MediaType.parseMediaType(r.getResponse().getContentType())
                        .isCompatibleWith(MediaType.APPLICATION_JSON))
                .isTrue();
        assertThat((String) JsonPath.read(body(r), "$.message")).isEqualTo(expected);
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
    void says_goodbye_to_alice() throws Exception { // AC1
        var r = call("Alice");
        assertMessage(r, "Goodbye, Alice!");
        assertThat(body(r)).isEqualTo("{\"message\":\"Goodbye, Alice!\"}");
    }

    @Test
    void surrounding_whitespace_is_trimmed() throws Exception { // AC2
        assertMessage(call("  Alice  "), "Goodbye, Alice!");
    }

    @Test
    void absent_name_defaults_to_world() throws Exception { // AC3
        var r = mvc.perform(get("/goodbye")).andReturn();
        assertMessage(r, "Goodbye, world!");
        assertThat(body(r)).isEqualTo("{\"message\":\"Goodbye, world!\"}");
    }

    @Test
    void empty_name_is_400_problem_not_defaulted() throws Exception { // AC4
        assertProblem(call(""), 400, "world");
    }

    @Test
    void raw_empty_name_in_uri_is_400_problem_not_defaulted() throws Exception { // AC4
        assertProblem(callRaw("/goodbye?name="), 400, "world");
    }

    @Test
    void whitespace_only_name_is_400_problem() throws Exception { // AC4
        assertProblem(call("   "), 400, "world");
    }

    @Test
    void name_of_51_characters_is_400_and_not_echoed() throws Exception { // AC5
        String name = "n".repeat(51);
        assertProblem(call(name), 400, name);
    }

    @Test
    void name_of_exactly_50_characters_is_accepted() throws Exception { // AC5 boundary
        String name = "n".repeat(50);
        assertMessage(call(name), "Goodbye, " + name + "!");
    }

    @Test
    void length_is_counted_after_trimming() throws Exception { // AC2/AC5
        String name = "n".repeat(50);
        assertMessage(call("  " + name + "  "), "Goodbye, " + name + "!");
    }

    @Test
    void fifty_emoji_name_is_accepted_because_length_counts_code_points() throws Exception { // AC5
        String name = "😀".repeat(50);
        assertMessage(call(name), "Goodbye, " + name + "!");
    }

    @Test
    void fifty_one_emoji_name_is_rejected() throws Exception { // AC5
        String name = "😀".repeat(51);
        assertProblem(call(name), 400, name);
    }

    @Test
    void raw_newline_in_name_is_400_and_not_echoed() throws Exception { // AC6
        assertProblem(callRaw("/goodbye?name=a%0Ab"), 400, "a\\nb", "a\nb");
    }

    @Test
    void raw_nul_in_name_is_400_and_not_echoed() throws Exception { // AC6
        assertProblem(callRaw("/goodbye?name=a%00b"), 400, "a\\u0000b", "a\u0000b");
    }

    @Test
    void control_characters_are_rejected_without_echoing_the_name() throws Exception { // AC6
        for (String name : List.of("a\nb", "a\u0000b", "Alice\n", "a\tb", "a\u007fb", "a\u0085b")) {
            assertProblem(call(name), 400, "Alice");
        }
    }

    @Test
    void non_ascii_names_are_returned_unchanged_as_utf_8() throws Exception { // AC7
        for (String name : List.of("Zoë", "李雷")) {
            var r = call(name);
            assertMessage(r, "Goodbye, " + name + "!");
            assertThat(r.getResponse().getContentAsByteArray()).contains(name.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void markup_is_returned_only_as_a_json_string_value() throws Exception { // AC8
        var r = call("<b>x</b>");
        assertMessage(r, "Goodbye, <b>x</b>!");
        assertThat(r.getResponse().getContentType()).startsWith("application/json");
    }

    @Test
    void post_is_405_problem_not_repeating_the_name() throws Exception { // AC9
        var r = mvc.perform(post("/goodbye").param("name", "Alice")).andReturn();
        assertProblem(r, 405, "Alice");
    }

    @Test
    void concurrent_requests_each_get_only_their_own_name() throws Exception { // AC11
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
                        .isEqualTo("{\"message\":\"Goodbye, caller" + i + "x!\"}");
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
