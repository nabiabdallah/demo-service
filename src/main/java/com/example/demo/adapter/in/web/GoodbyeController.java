package com.example.demo.adapter.in.web;

import com.example.demo.application.port.in.FarewellCommand;
import com.example.demo.application.port.in.FarewellUseCase;
import com.example.demo.domain.PersonName;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /goodbye?name=X}. The name is user data: it is never logged and never echoed in error responses. An
 * absent name means {@code world}; a present one follows {@link PersonName}'s rules, and a repeated one is 400. A
 * present but blank name is a 400, so no {@code defaultValue} is used (Spring would also apply it to an empty value).
 */
@RestController
public class GoodbyeController {

    private static final PersonName DEFAULT_NAME = new PersonName("world");

    private final FarewellUseCase farewell;

    public GoodbyeController(FarewellUseCase farewell) {
        this.farewell = farewell;
    }

    record GoodbyeResponse(String message) {}

    @GetMapping(path = "/goodbye", produces = MediaType.APPLICATION_JSON_VALUE)
    GoodbyeResponse goodbye(@RequestParam(name = "name", required = false) String rawName, HttpServletRequest request) {
        var name = rawName == null ? DEFAULT_NAME : new PersonName(NameParameter.single(request, rawName));
        return new GoodbyeResponse(farewell.farewell(new FarewellCommand(name)).message());
    }
}
