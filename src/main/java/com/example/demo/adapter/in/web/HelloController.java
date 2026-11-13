package com.example.demo.adapter.in.web;

import com.example.demo.application.port.in.GreetCommand;
import com.example.demo.application.port.in.GreetUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /hello?name=X}. The name is user data: it is never logged and never echoed in error
 * responses. Rules: no control characters (checked on the raw value), then trimmed, then 1..50 code points.
 */
@RestController
public class HelloController {

    private static final int MAX_NAME_CODE_POINTS = 50;

    private final GreetUseCase greet;

    public HelloController(GreetUseCase greet) {
        this.greet = greet;
    }

    record HelloResponse(String greeting) {}

    @GetMapping(path = "/hello", produces = MediaType.APPLICATION_JSON_VALUE)
    HelloResponse hello(@RequestParam("name") String rawName) {
        var name = validate(rawName);
        return new HelloResponse(greet.greet(new GreetCommand(name)).greeting());
    }

    private static String validate(String raw) {
        if (raw.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        var name = raw.strip();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > MAX_NAME_CODE_POINTS) {
            throw invalid();
        }
        return name;
    }

    private static ErrorResponseException invalid() {
        return new ErrorResponseException(
                HttpStatus.BAD_REQUEST, ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid name"), null);
    }
}
