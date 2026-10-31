package com.paymentplatform.common_lib.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FixedWindowRateLimiterTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
    }

    @Test
    void firstRequestIsAllowedAndStartsTheWindow() {
        when(ops.increment(anyString())).thenReturn(1L);

        RateLimitResult result = new FixedWindowRateLimiter(redis).check("k", 5, 60);

        assertThat(result.isAllowed()).isTrue();
        assertThat(result.remaining()).isEqualTo(4);
        verify(redis).expire(anyString(), eq(Duration.ofSeconds(60)));
    }

    @Test
    void requestOverTheLimitIsDeniedEvenWhenRedisReturnsNoTtl() {
        // regression: "ttl != null & ttl > 0" threw a NullPointerException when the ttl was null
        when(ops.increment(anyString())).thenReturn(6L);
        when(redis.getExpire(anyString(), eq(TimeUnit.SECONDS))).thenReturn(null);

        RateLimitResult result = new FixedWindowRateLimiter(redis).check("k", 5, 60);

        assertThat(result.isAllowed()).isFalse();
        assertThat(result.retryAfterSeconds()).isEqualTo(60);
    }
}
