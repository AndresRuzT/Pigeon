package io.github.andres.pigeon.infrastructure;

import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.infrastructure.adapter.out.cache.RedisRateLimiterAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisRateLimiterAdapterTest {

    @Mock
    private RedisOperations<String, String> redisOperations;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisRateLimiterAdapter rateLimiter;

    @BeforeEach
    void setUp() {
        when(redisOperations.opsForValue()).thenReturn(valueOperations);
        rateLimiter = new RedisRateLimiterAdapter(redisOperations);
    }

    @Test
    @DisplayName("Should allow requests under standard limit (10/hr) and set expiration on first count")
    void shouldAllowStandardRequestsUnderLimit() {
        when(valueOperations.increment("rate:std:cus_123")).thenReturn(1L);

        boolean allowed = rateLimiter.isAllowed("cus_123", EventType.TRANSFER_COMPLETED);

        assertThat(allowed).isTrue();
        verify(redisOperations).expire(eq("rate:std:cus_123"), eq(Duration.ofHours(1)));
    }

    @Test
    @DisplayName("Should reject requests exceeding standard limit (11th request in 1 hour)")
    void shouldRejectStandardRequestsExceedingLimit() {
        when(valueOperations.increment("rate:std:cus_123")).thenReturn(11L);

        boolean allowed = rateLimiter.isAllowed("cus_123", EventType.TRANSFER_COMPLETED);

        assertThat(allowed).isFalse();
    }

    @Test
    @DisplayName("Should allow OTP requests under limit (5/10min)")
    void shouldAllowOtpRequestsUnderLimit() {
        when(valueOperations.increment("rate:otp:cus_123")).thenReturn(5L);

        boolean allowed = rateLimiter.isAllowed("cus_123", EventType.OTP_REQUESTED);

        assertThat(allowed).isTrue();
    }

    @Test
    @DisplayName("Should reject OTP requests exceeding limit (6th request in 10 minutes)")
    void shouldRejectOtpRequestsExceedingLimit() {
        when(valueOperations.increment("rate:otp:cus_123")).thenReturn(6L);

        boolean allowed = rateLimiter.isAllowed("cus_123", EventType.OTP_REQUESTED);

        assertThat(allowed).isFalse();
    }

    @Test
    @DisplayName("Should fail open and allow request if Redis throws exception")
    void shouldFailOpenOnRedisException() {
        when(valueOperations.increment(any())).thenThrow(new RedisConnectionFailureException("Connection refused"));

        boolean allowed = rateLimiter.isAllowed("cus_123", EventType.OTP_REQUESTED);

        assertThat(allowed).isTrue();
    }
}
