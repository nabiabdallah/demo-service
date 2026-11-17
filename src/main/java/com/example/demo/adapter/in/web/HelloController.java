package com.example.demo.adapter.in.web;

import com.example.demo.application.port.in.GreetCommand;
import com.example.demo.application.port.in.GreetUseCase;
import com.example.demo.domain.PersonName;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /hello?name=X}. The name is user data: it is never logged and never echoed in error responses. Its
 * rules are {@link PersonName}'s; a missing name is 400 (Spring's own problem), a repeated one too.
 */
@RestController
public class HelloController {

    private final GreetUseCase greet;

    public HelloController(GreetUseCase greet) {
        this.greet = greet;
    }

    record HelloResponse(String greeting) {}

    @GetMapping(path = "/hello", produces = MediaType.APPLICATION_JSON_VALUE)
    HelloResponse hello(@RequestParam("name") String rawName, HttpServletRequest request) {
        var name = new PersonName(NameParameter.single(request, rawName));
        return new HelloResponse(greet.greet(new GreetCommand(name)).greeting());
    }
}
