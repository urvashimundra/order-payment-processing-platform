package com.paymentplatform.common_lib.exception;

/** Thrown when credentials are wrong. Mapped to HTTP 401 by GlobalExceptionHandler. */
public class AuthenticationFailedException extends RuntimeException {
    public AuthenticationFailedException(String message) {
        super(message);
    }
}
