package com.example.demo.application.port.in;

/** Input of {@link FarewellUseCase}: an already validated, trimmed name. */
public record FarewellCommand(String name) {}
