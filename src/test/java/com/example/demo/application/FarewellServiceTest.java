package com.example.demo.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.application.port.in.FarewellCommand;
import com.example.demo.application.port.in.FarewellUseCase;
import com.example.demo.domain.PersonName;
import org.junit.jupiter.api.Test;

class FarewellServiceTest {

    private final FarewellUseCase service = new FarewellService();

    @Test
    void says_goodbye_to_the_given_name() { // AC1
        assertThat(service.farewell(new FarewellCommand(new PersonName("Alice")))
                        .message())
                .isEqualTo("Goodbye, Alice!");
    }

    @Test
    void keeps_non_ascii_and_markup_unchanged() { // AC7, AC8
        assertThat(service.farewell(new FarewellCommand(new PersonName("Zoë"))).message())
                .isEqualTo("Goodbye, Zoë!");
        assertThat(service.farewell(new FarewellCommand(new PersonName("李雷"))).message())
                .isEqualTo("Goodbye, 李雷!");
        assertThat(service.farewell(new FarewellCommand(new PersonName("<b>x</b>")))
                        .message())
                .isEqualTo("Goodbye, <b>x</b>!");
    }
}
