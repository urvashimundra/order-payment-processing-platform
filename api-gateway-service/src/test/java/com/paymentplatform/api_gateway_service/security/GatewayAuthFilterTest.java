package com.paymentplatform.api_gateway_service.security;

import com.paymentplatform.common_lib.ratelimit.RateLimitResult;
import com.paymentplatform.common_lib.ratelimit.RateLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GatewayAuthFilterTest {

    private JwtAuthHandler jwtAuthHandler;
    private PublicRouteMatcher publicRouteMatcher;
    private RateLimiter rateLimiter;
    private GatewayAuthFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        jwtAuthHandler = mock(JwtAuthHandler.class);
        publicRouteMatcher = mock(PublicRouteMatcher.class);
        rateLimiter = mock(RateLimiter.class);
        chain = mock(FilterChain.class);
        filter = new GatewayAuthFilter(jwtAuthHandler, mock(ApiKeyAuthHandler.class), publicRouteMatcher,
                mock(ObjectMapper.class), rateLimiter);
        ReflectionTestUtils.setField(filter, "authRequestsPerMinute", 5);
    }

    @Test
    void identityHeadersSentByTheClientAreReplacedOrRemoved() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/orders");
        request.addHeader("Authorization", "Bearer abc");
        request.addHeader("X-Merchant-Id", "someone-elses-merchant");   // forged
        request.addHeader("X-User-Role", "ADMIN");                       // forged
        request.addHeader("X-Environment", "LIVE");                      // forged
        when(jwtAuthHandler.authenticate("abc")).thenReturn(Map.of("X-Merchant-Id", "real-merchant", "X-User-Role", "OWNER"));

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        ArgumentCaptor<ServletRequest> forwarded = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(forwarded.capture(), any());
        var seen = (jakarta.servlet.http.HttpServletRequest) forwarded.getValue();
        assertThat(seen.getHeader("X-Merchant-Id")).isEqualTo("real-merchant");
        assertThat(seen.getHeader("X-User-Role")).isEqualTo("OWNER");
        assertThat(seen.getHeader("X-Environment")).as("forged header must be stripped").isNull();
    }

    @Test
    void publicRouteStillStripsForgedIdentityHeaders() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/webhook/success");
        request.addHeader("X-Merchant-Id", "forged");
        when(publicRouteMatcher.isPublic("/webhook/success")).thenReturn(true);

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        ArgumentCaptor<ServletRequest> forwarded = ArgumentCaptor.forClass(ServletRequest.class);
        verify(chain).doFilter(forwarded.capture(), any());
        assertThat(((jakarta.servlet.http.HttpServletRequest) forwarded.getValue()).getHeader("X-Merchant-Id")).isNull();
    }

    @Test
    void loginAttemptsAreRateLimitedPerIp() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/auth/login");
        when(publicRouteMatcher.isPublic("/v1/auth/login")).thenReturn(true);
        when(rateLimiter.check(anyString(), anyInt(), anyLong())).thenReturn(RateLimitResult.denied(9));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("Retry-After")).isEqualTo("9");
        verifyNoInteractions(chain);
    }

    @Test
    void requestWithoutCredentialsIsRejectedWith401() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("GET", "/v1/orders"), response, chain);
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(chain);
    }
}
