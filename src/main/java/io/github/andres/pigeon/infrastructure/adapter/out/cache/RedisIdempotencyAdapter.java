package io.github.andres.pigeon.infrastructure.adapter.out.cache;

import io.github.andres.pigeon.application.port.out.IdempotencyStore;
import io.github.andres.pigeon.domain.vo.IdempotencyKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Component
public class RedisIdempotencyAdapter implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyAdapter.class);
    private static final String KEY_PREFIX = "pigeon:idempotency:";

    private final RedisOperations<String, String> redisTemplate;

    public RedisIdempotencyAdapter(RedisOperations<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public AcquisitionResult acquireOrFind(String clientId, IdempotencyKey key, String payloadHash, Duration ttl) {
        String redisKey = formatKey(clientId, key);
        try {
            String initialValue = payloadHash + ":PENDING";
            Boolean success = redisTemplate.opsForValue().setIfAbsent(redisKey, initialValue, ttl);
            if (Boolean.TRUE.equals(success)) {
                return new AcquisitionResult(LockResult.ACQUIRED, Optional.empty());
            }

            // Key already exists, retrieve it
            String rawValue = redisTemplate.opsForValue().get(redisKey);
            return new AcquisitionResult(LockResult.EXISTS, parseStored(rawValue));
        } catch (Exception ex) {
            log.warn("Redis idempotency acquire failed for key {}. Falling back to PostgreSQL source of truth: {}",
                    redisKey, ex.getMessage());
            return new AcquisitionResult(LockResult.STORE_UNAVAILABLE, Optional.empty());
        }
    }

    @Override
    public Optional<StoredIdempotency> find(String clientId, IdempotencyKey key) {
        String redisKey = formatKey(clientId, key);
        try {
            String rawValue = redisTemplate.opsForValue().get(redisKey);
            return parseStored(rawValue);
        } catch (Exception ex) {
            log.warn("Redis idempotency read failed for key {}. Falling back to PostgreSQL source of truth: {}",
                    redisKey, ex.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void save(String clientId, IdempotencyKey key, String payloadHash, UUID notificationId, Duration ttl) {
        String redisKey = formatKey(clientId, key);
        String rawValue = payloadHash + ":" + notificationId;
        try {
            redisTemplate.opsForValue().set(redisKey, rawValue, ttl);
        } catch (Exception ex) {
            log.warn("Redis idempotency write failed for key {}. Transaction integrity maintained by PostgreSQL: {}",
                    redisKey, ex.getMessage());
        }
    }

    @Override
    public void evict(String clientId, IdempotencyKey key) {
        String redisKey = formatKey(clientId, key);
        try {
            redisTemplate.delete(redisKey);
        } catch (Exception ex) {
            log.warn("Redis idempotency eviction failed for key {}: {}", redisKey, ex.getMessage());
        }
    }

    private Optional<StoredIdempotency> parseStored(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return Optional.empty();
        }
        int delimiterIndex = rawValue.indexOf(':');
        if (delimiterIndex == -1) {
            return Optional.empty();
        }

        String payloadHash = rawValue.substring(0, delimiterIndex);
        String idPart = rawValue.substring(delimiterIndex + 1);
        if ("PENDING".equals(idPart)) {
            // Still in flight
            return Optional.of(new StoredIdempotency(payloadHash, null));
        }

        try {
            UUID notificationId = UUID.fromString(idPart);
            return Optional.of(new StoredIdempotency(payloadHash, notificationId));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    private String formatKey(String clientId, IdempotencyKey key) {
        return KEY_PREFIX + clientId + ":" + key.value();
    }
}
