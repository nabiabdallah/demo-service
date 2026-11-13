package com.example.demo.application.port.in;

/** Use case: build a greeting for a name. The caller has already validated the name. */
public interface GreetUseCase {

    GreetResult greet(GreetCommand command);
}
