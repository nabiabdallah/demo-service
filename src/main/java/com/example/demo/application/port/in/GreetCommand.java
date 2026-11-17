package com.example.demo.application.port.in;

import com.example.demo.domain.PersonName;
import java.util.Objects;

/** Input of {@link GreetUseCase}. A {@link PersonName} is valid by construction, so the command is too. */
public record GreetCommand(PersonName name) {

    public GreetCommand {
        Objects.requireNonNull(name, "name");
    }
}
