package com.example.demo.domain;

/** A name that breaks the rules of {@link PersonName}. The message never repeats the name. */
public final class InvalidNameException extends InvalidInputException {

    public InvalidNameException() {
        super("Invalid name");
    }
}
