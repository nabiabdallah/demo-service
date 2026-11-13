package com.example.demo.application.port.in;

/** Input of {@link GreetUseCase}: an already validated, trimmed name. */
public record GreetCommand(String name) {}
