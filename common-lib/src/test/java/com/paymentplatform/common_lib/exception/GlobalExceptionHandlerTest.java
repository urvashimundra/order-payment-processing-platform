package com.paymentplatform.common_lib.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void wrongHttpMethodIs405NotAServerError() {
        // regression: the catch-all handler used to turn this into HTTP 500
        var response = handler.handleMethodNotSupported(new HttpRequestMethodNotSupportedException("GET"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().errorCode()).isEqualTo("METHOD_NOT_ALLOWED");
    }

    @Test
    void badCredentialsAre401() {
        var response = handler.handleAuthenticationFailed(new AuthenticationFailedException("Invalid email or password"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().errorCode()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void businessRuleViolationIs400() {
        var response = handler.handleBusinessRuleViolation(new BusinessRuleViolationException("ORDER_NOT_PAYABLE", "nope"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().errorCode()).isEqualTo("ORDER_NOT_PAYABLE");
    }

    @Test
    void duplicateIs409() {
        var response = handler.handleDuplicateResources(new DuplicateResourceException("DUP", "exists"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rateLimitIs429WithRetryAfterHeader() {
        var response = handler.handleRateLimitException(new RateLimitException("slow down", 7));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("7");
    }

    @Test
    void unexpectedErrorsAreStill500() {
        var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/x");
        var response = handler.handleUnexpected(new IllegalStateException("boom"), request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }
}
