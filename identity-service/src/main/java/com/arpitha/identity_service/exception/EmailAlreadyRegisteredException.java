package com.arpitha.identity_service.exception;

public class EmailAlreadyRegisteredException extends RuntimeException {
    public EmailAlreadyRegisteredException(String email) {
        super("An account with email " + email + " already exists");
    }
}
