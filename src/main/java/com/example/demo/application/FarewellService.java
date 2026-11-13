package com.example.demo.application;

import com.example.demo.application.port.in.FarewellCommand;
import com.example.demo.application.port.in.FarewellResult;
import com.example.demo.application.port.in.FarewellUseCase;

/** Stateless farewell use case. Trusts its caller to have validated the name. */
public class FarewellService implements FarewellUseCase {

    @Override
    public FarewellResult farewell(FarewellCommand command) {
        return new FarewellResult("Goodbye, " + command.name() + "!");
    }
}
