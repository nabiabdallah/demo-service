package com.example.demo.application;

import com.example.demo.application.port.in.FarewellCommand;
import com.example.demo.application.port.in.FarewellResult;
import com.example.demo.application.port.in.FarewellUseCase;

/** Stateless farewell use case. The name is valid by construction ({@link com.example.demo.domain.PersonName}). */
public class FarewellService implements FarewellUseCase {

    @Override
    public FarewellResult farewell(FarewellCommand command) {
        return new FarewellResult("Goodbye, " + command.name().value() + "!");
    }
}
