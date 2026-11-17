package com.example.demo.domain;

/**
 * Input that breaks a value object's rules. The web adapter maps it to 400 (other {@link DomainException}s are 422).
 * Subclass per value, e.g. {@link InvalidNameException}; the message must never repeat the rejected input.
 */
public abstract class InvalidInputException extends DomainException {

    protected InvalidInputException(String message) {
        super(message);
    }
}
