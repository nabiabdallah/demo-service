package com.example.demo.adapter.in.web;

import com.example.demo.application.port.in.FarewellCommand;
import com.example.demo.application.port.in.FarewellUseCase;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /goodbye?name=X}. The name is user data: it is never logged and never echoed in error
 * responses. An absent name means {@code world}; a present name follows the same rules as {@code /hello}
 * (no control characters on the raw value, then trimmed, then 1..50 code points). A present but blank name is
 * a 400, so no {@code defaultValue} is used here (Spring would also apply it to an empty value).
 */
@RestController
public class GoodbyeController {

    private static final int MAX_NAME_CODE_POINTS = 50;
    private static final String DEFAULT_NAME = "world";

    private final FarewellUseCase farewell;

    public GoodbyeController(FarewellUseCase farewell) {
        this.farewell = farewell;
    }

    record GoodbyeResponse(String message) {}

    @GetMapping(path = "/goodbye", produces = MediaType.APPLICATION_JSON_VALUE)
    GoodbyeResponse goodbye(@RequestParam(name = "name", required = false) String rawName) {
        var name = rawName == null ? DEFAULT_NAME : validate(rawName);
        return new GoodbyeResponse(farewell.farewell(new FarewellCommand(name)).message());
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
