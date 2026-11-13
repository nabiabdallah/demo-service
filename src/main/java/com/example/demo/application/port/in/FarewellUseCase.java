package com.example.demo.application.port.in;

/** Use case: build a farewell for a name. The caller has already validated the name. */
public interface FarewellUseCase {

    FarewellResult farewell(FarewellCommand command);
}
