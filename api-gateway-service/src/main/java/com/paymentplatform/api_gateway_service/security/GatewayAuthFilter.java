package com.paymentplatform.api_gateway_service.security;

import com.paymentplatform.common_lib.exception.RateLimitException;
import com.paymentplatform.common_lib.ratelimit.RateLimitResult;
import com.paymentplatform.common_lib.ratelimit.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE+1)
public class GatewayAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String BASIC_PREFIX = "Basic ";

    private final JwtAuthHandler jwtAuthHandler;
    private final ApiKeyAuthHandler apiKeyAuthHandler;
    private final PublicRouteMatcher publicRouteMatcher;
    private final ObjectMapper objectMapper;
    private final RateLimiter rateLimiter;

    // Headers that carry the *verified* identity. Clients must never be able to set them.
    private static final List<String> IDENTITY_HEADERS =
            List.of("X-Merchant-Id", "X-Environment", "X-Key-Id", "X-User-Role");

    @Value("${app.rate-limit.use-case.auth.requests-per-minute:30}")
    private int authRequestsPerMinute;


    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        log.info("Incoming request: {}", request.getRequestURI());

        if (publicRouteMatcher.isPublic(request.getRequestURI())) {
            // Brute-force protection for signup/login: limit attempts per client IP
            if (request.getRequestURI().startsWith("/v1/auth/")) {
                RateLimitResult attempt = rateLimiter.check("auth-ip:" + request.getRemoteAddr(), authRequestsPerMinute, 60);
                if (!attempt.isAllowed()) {
                    response.setHeader("Retry-After", String.valueOf(attempt.retryAfterSeconds()));
                    reject(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED",
                            "Too many attempts, please try again later");
                    return;
                }
            }
            filterChain.doFilter(withoutIdentityHeaders(request), response);
            return;
        }

        String authHeader = request.getHeader("Authorization");

        try {
            Map<String, String> identityHeaders = Map.of();
            if (authHeader != null && authHeader.startsWith(BASIC_PREFIX)) {
                identityHeaders = apiKeyAuthHandler.authenticate(authHeader, response);
            } else if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
                identityHeaders = jwtAuthHandler.authenticate(authHeader.substring(BEARER_PREFIX.length()));
            } else {
                reject(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Missing or invalid Authorization header");
                return;
            }

            HeaderAugmentingRequestWrapper wrapped = withoutIdentityHeaders(request);
            identityHeaders.forEach(wrapped::putHeader);

            filterChain.doFilter(wrapped, response);
        } catch (RateLimitException e) {
            response.setHeader("Retry-After", String.valueOf(e.getRetryAfterSeconds()));
            reject(response, HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", e.getMessage());
        } catch (GatewayAuthenticationException e) {
            reject(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", e.getMessage());
        } catch (Exception e) {
            log.warn("Gateway auth failed for path={}", request.getRequestURI(), e);
            reject(response, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Invalid credentials");
        }

    }

    /** FIX (security): strip any identity headers the client sent, so they can't be forged. */
    private HeaderAugmentingRequestWrapper withoutIdentityHeaders(HttpServletRequest request) {
        HeaderAugmentingRequestWrapper wrapper = new HeaderAugmentingRequestWrapper(request);
        IDENTITY_HEADERS.forEach(wrapper::blockHeader);
        return wrapper;
    }

    private void reject(HttpServletResponse response, HttpStatus status, String errorCode, String message)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Map.of("errorCode", errorCode, "errorDescription", message));
    }
}

























