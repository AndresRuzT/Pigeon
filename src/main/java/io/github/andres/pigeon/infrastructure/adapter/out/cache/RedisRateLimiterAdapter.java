package io.github.andres.pigeon.infrastructure.adapter.out.cache;

import io.github.andres.pigeon.application.port.out.RateLimiterPort;
import io.github.andres.pigeon.domain.enums.EventType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Sliding/Fixed window rate limiter backed by Redis.
 * Buckets:
 * - OTP / Security events: 5 requests per 10 minutes (mitigates SMS pumping / brute-force abuse).
 * - Standard events: 10 requests per 1 hour per customer.
 * Fail-open on Redis communication failures to preserve banking service availability.
 */
@Component
public class RedisRateLimiterAdapter implements RateLimiterPort {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiterAdapter.class);

    private static final int OTP_MAX_REQUESTS = 5;
    private static final Duration OTP_WINDOW = Duration.ofMinutes(10);

    private static final int STANDARD_MAX_REQUESTS = 10;
    private static final Duration STANDARD_WINDOW = Duration.ofHours(1);

    private final RedisOperations<String, String> redisOperations;

    @Autowired
    public RedisRateLimiterAdapter(RedisOperations<String, String> redisOperations) {
        this.redisOperations = redisOperations;
    }

    @Override
    public boolean isAllowed(String customerId, EventType eventType) {
        boolean isOtp = (eventType == EventType.OTP_REQUESTED);
        String bucketKey = isOtp ? "rate:otp:" + customerId : "rate:std:" + customerId;
        int maxAllowed = isOtp ? OTP_MAX_REQUESTS : STANDARD_MAX_REQUESTS;
        Duration window = isOtp ? OTP_WINDOW : STANDARD_WINDOW;

        try {
            Long currentCount = redisOperations.opsForValue().increment(bucketKey);
            if (currentCount != null && currentCount == 1L) {
                redisOperations.expire(bucketKey, window);
            }

            if (currentCount != null && currentCount > maxAllowed) {
                log.warn("Rate limit exceeded for customer {} on bucket {}. Count: {}, Limit: {}",
                        customerId, bucketKey, currentCount, maxAllowed);
                return false;
            }
            return true;
        } catch (Exception ex) {
            log.error("Redis rate limiter unavailable ({}: {}). Failing open for customer {}.",
                    ex.getClass().getSimpleName(), ex.getMessage(), customerId);
            return true;
        }
    }
}
