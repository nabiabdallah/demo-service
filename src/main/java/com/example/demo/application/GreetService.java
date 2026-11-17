package com.example.demo.application;

import com.example.demo.application.port.in.GreetCommand;
import com.example.demo.application.port.in.GreetResult;
import com.example.demo.application.port.in.GreetUseCase;

/** Stateless greeting use case. The name is valid by construction ({@link com.example.demo.domain.PersonName}). */
public class GreetService implements GreetUseCase {

    @Override
    public GreetResult greet(GreetCommand command) {
        return new GreetResult("Hello, " + command.name().value() + "!");
    }
}
