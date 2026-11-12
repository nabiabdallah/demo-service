package com.example.demo.domain;

/**
 * Base type for business-rule violations. The web adapter maps these to 4xx responses and returns
 * the message to the client, so messages must never contain raw identifiers, amounts or other PII.
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }
}
