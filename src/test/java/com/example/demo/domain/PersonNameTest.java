package com.example.demo.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** The name rules of specs 0001 and 0002, owned by the domain (no Spring). */
class PersonNameTest {

    @Test
    void keeps_a_valid_name() {
        assertThat(new PersonName("Alice").value()).isEqualTo("Alice");
    }

    @Test
    void trims_surrounding_whitespace() {
        assertThat(new PersonName("  Alice  ").value()).isEqualTo("Alice");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t \t"})
    void rejects_blank(String raw) {
        assertThatThrownBy(() -> new PersonName(raw)).isInstanceOf(InvalidNameException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\nb", "a\u0000b", "Alice\n", "\u007fx"})
    void rejects_control_characters_anywhere_even_where_trimming_would_remove_them(String raw) {
        assertThatThrownBy(() -> new PersonName(raw)).isInstanceOf(InvalidNameException.class);
    }

    @Test
    void length_is_counted_in_code_points_after_trimming() {
        assertThat(new PersonName("  " + "a".repeat(50) + "  ").value()).hasSize(50);
        assertThat(new PersonName("😀".repeat(50)).value()).isEqualTo("😀".repeat(50));
        assertThatThrownBy(() -> new PersonName("a".repeat(51))).isInstanceOf(InvalidNameException.class);
        assertThatThrownBy(() -> new PersonName("😀".repeat(51))).isInstanceOf(InvalidNameException.class);
    }

    @Test
    void rejects_null() {
        assertThatThrownBy(() -> new PersonName(null)).isInstanceOf(InvalidNameException.class);
    }

    @Test
    void neither_the_error_nor_toString_repeats_the_name() {
        assertThatThrownBy(() -> new PersonName("Mallory\n"))
                .isInstanceOf(DomainException.class)
                .hasMessage("Invalid name");
        assertThat(new PersonName("Alice").toString()).doesNotContain("Alice");
    }
}
