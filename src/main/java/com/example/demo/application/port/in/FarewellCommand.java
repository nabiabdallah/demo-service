package com.example.demo.application.port.in;

import com.example.demo.domain.PersonName;
import java.util.Objects;

/** Input of {@link FarewellUseCase}. A {@link PersonName} is valid by construction, so the command is too. */
public record FarewellCommand(PersonName name) {

    public FarewellCommand {
        Objects.requireNonNull(name, "name");
    }
}
