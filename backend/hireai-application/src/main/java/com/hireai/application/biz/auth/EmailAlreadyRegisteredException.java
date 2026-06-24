package com.hireai.application.biz.auth;

/** Raised when registration is attempted with an email that already has an account. Maps to 409. */
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException() {
        super("Email already registered");
    }
}
