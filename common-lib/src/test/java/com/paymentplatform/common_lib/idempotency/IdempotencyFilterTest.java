package com.paymentplatform.common_lib.idempotency;

import com.paymentplatform.common_lib.context.MerchantContext;
import com.paymentplatform.common_lib.exception.IdempotencyConflictException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class IdempotencyFilterTest {

    /** Simple in-memory stand-in for Redis. */
    static class InMemoryStore implements IdempotencyStore {
        final Map<String, String> data = new HashMap<>();
        public boolean setIfAbsent(String key, Duration ttl) { return data.putIfAbsent(key, IN_PROGRESS) == null; }
        public void store(String key, String value, Duration ttl) { data.put(key, value); }
        public Optional<String> get(String key) { return Optional.ofNullable(data.get(key)); }
        public void delete(String key) { data.remove(key); }
    }

    private InMemoryStore store;
    private HandlerExceptionResolver resolver;
    private IdempotencyFilter filter;
    private AtomicInteger chainCalls;
    private FilterChain chain201;

    @BeforeEach
    void setUp() {
        store = new InMemoryStore();
        resolver = mock(HandlerExceptionResolver.class);
        filter = new IdempotencyFilter(new MerchantContext(), store, resolver);
        chainCalls = new AtomicInteger();
        chain201 = (req, res) -> {
            chainCalls.incrementAndGet();
            HttpServletResponse r = (HttpServletResponse) res;
            r.setStatus(201);
            r.setContentType("application/json");
            r.getWriter().write("{\"id\":\"abc\"}");
        };
    }

    private static MockHttpServletRequest post(String path, String key) {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
        if (key != null) req.addHeader("X-Idempotency-Key", key);
        return req;
    }

    @Test
    void requestWithoutKeyIsNotTouched() throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(post("/v1/orders", null), res, chain201);
        assertThat(chainCalls.get()).isEqualTo(1);
        assertThat(store.data).isEmpty();
    }

    @Test
    void secondRequestWithSameKeyReplaysTheFirstResponseWithoutRunningTheHandler() throws Exception {
        MockHttpServletResponse first = new MockHttpServletResponse();
        filter.doFilter(post("/v1/orders", "k1"), first, chain201);

        MockHttpServletResponse second = new MockHttpServletResponse();
        filter.doFilter(post("/v1/orders", "k1"), second, chain201);

        assertThat(chainCalls.get()).as("handler must run only once").isEqualTo(1);
        assertThat(first.getStatus()).isEqualTo(201);
        assertThat(second.getStatus()).isEqualTo(201);
        assertThat(second.getContentAsString()).isEqualTo("{\"id\":\"abc\"}");
    }

    @Test
    void sameKeyOnADifferentEndpointIsNotReplayed() throws Exception {
        filter.doFilter(post("/v1/orders", "shared"), new MockHttpServletResponse(), chain201);
        filter.doFilter(post("/v1/payments", "shared"), new MockHttpServletResponse(), chain201);
        assertThat(chainCalls.get()).isEqualTo(2);
    }

    @Test
    void failedRequestsAreNotCachedSoTheClientCanRetry() throws Exception {
        FilterChain failing = (req, res) -> {
            chainCalls.incrementAndGet();
            ((HttpServletResponse) res).setStatus(400);
        };
        filter.doFilter(post("/v1/orders", "k2"), new MockHttpServletResponse(), failing);
        filter.doFilter(post("/v1/orders", "k2"), new MockHttpServletResponse(), failing);
        assertThat(chainCalls.get()).isEqualTo(2);
    }

    @Test
    void requestWhileAnotherIsInProgressGetsAConflict() throws Exception {
        store.setIfAbsent("POST:/v1/orders:busy", Duration.ofSeconds(30));
        filter.doFilter(post("/v1/orders", "busy"), new MockHttpServletResponse(), chain201);
        assertThat(chainCalls.get()).isZero();
        verify(resolver).resolveException(any(), any(), isNull(), any(IdempotencyConflictException.class));
    }

    @Test
    void corruptStoredValueDoesNotCrashTheFilter() {
        // regression: the old code forgot to return and threw StringIndexOutOfBoundsException
        store.store("POST:/v1/orders:bad", "no-separator-here", Duration.ofHours(1));
        assertThatCode(() -> filter.doFilter(post("/v1/orders", "bad"), new MockHttpServletResponse(), chain201))
                .doesNotThrowAnyException();
        verify(resolver).resolveException(any(), any(), isNull(), any(IdempotencyConflictException.class));
    }

    @Test
    void getRequestsAreIgnored() throws Exception {
        MockHttpServletRequest get = new MockHttpServletRequest("GET", "/v1/orders");
        get.addHeader("X-Idempotency-Key", "k");
        filter.doFilter(get, new MockHttpServletResponse(), chain201);
        assertThat(store.data).isEmpty();
        verify(resolver, never()).resolveException(any(), any(), any(), any());
    }
}
