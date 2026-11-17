package com.example.demo.domain;

/**
 * A person's name as given to {@code /hello} and {@code /goodbye} (specs 0001, 0002): no control characters
 * (checked on the raw value, so trimming can't hide one), then trimmed, then 1..50 Unicode code points (a
 * character outside the Basic Multilingual Plane, such as an emoji, counts once). The name is user data:
 * {@link #toString()} never shows it, and errors never repeat it.
 */
public record PersonName(String value) {

    static final int MAX_CODE_POINTS = 50;

    public PersonName {
        if (value == null || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new InvalidNameException();
        }
        value = value.strip();
        if (value.isEmpty() || value.codePointCount(0, value.length()) > MAX_CODE_POINTS) {
            throw new InvalidNameException();
        }
    }

    @Override
    public String toString() {
        return "PersonName[****]";
    }
}
