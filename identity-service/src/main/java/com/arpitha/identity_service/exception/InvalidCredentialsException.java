package com.arpitha.identity_service.exception;

/** Deliberately the same for an unknown email and a wrong password. */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() {
        super("Invalid email or password");
    }
}
