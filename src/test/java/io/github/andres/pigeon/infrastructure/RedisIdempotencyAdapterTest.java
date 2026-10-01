package io.github.andres.pigeon.infrastructure;

import io.github.andres.pigeon.application.port.out.IdempotencyStore;
import io.github.andres.pigeon.domain.vo.IdempotencyKey;
import io.github.andres.pigeon.infrastructure.adapter.out.cache.RedisIdempotencyAdapter;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisIdempotencyAdapterTest {

    @Mock
    private RedisOperations<String, String> redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private RedisIdempotencyAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new RedisIdempotencyAdapter(redisTemplate);
    }

    @Test
    @DisplayName("Should acquire lock when key is new via setIfAbsent")
    void shouldAcquireLockWhenNew() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(eq("pigeon:idempotency:client-a:key-1"), eq("hash-123:PENDING"), any()))
                .thenReturn(true);

        IdempotencyStore.AcquisitionResult result = adapter.acquireOrFind("client-a", IdempotencyKey.of("key-1"), "hash-123", Duration.ofHours(24));

        assertThat(result.result()).isEqualTo(IdempotencyStore.LockResult.ACQUIRED);
        assertThat(result.existing()).isEmpty();
    }

    @Test
    @DisplayName("Should return EXISTS when setIfAbsent returns false and stored value is present")
    void shouldReturnExistsWhenAlreadyPresent() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        UUID notificationId = UUID.randomUUID();
        when(valueOperations.setIfAbsent(eq("pigeon:idempotency:client-a:key-1"), eq("hash-123:PENDING"), any()))
                .thenReturn(false);
        when(valueOperations.get("pigeon:idempotency:client-a:key-1")).thenReturn("hash-123:" + notificationId);

        IdempotencyStore.AcquisitionResult result = adapter.acquireOrFind("client-a", IdempotencyKey.of("key-1"), "hash-123", Duration.ofHours(24));

        assertThat(result.result()).isEqualTo(IdempotencyStore.LockResult.EXISTS);
        assertThat(result.existing()).isPresent();
        assertThat(result.existing().get().notificationId()).isEqualTo(notificationId);
    }

    @Test
    @DisplayName("Should return STORE_UNAVAILABLE on Redis connection failure during acquire")
    void shouldHandleRedisFailureOnAcquire() {
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("Connection refused"));

        IdempotencyStore.AcquisitionResult result = adapter.acquireOrFind("client-a", IdempotencyKey.of("key-1"), "hash-123", Duration.ofHours(24));

        assertThat(result.result()).isEqualTo(IdempotencyStore.LockResult.STORE_UNAVAILABLE);
        assertThat(result.existing()).isEmpty();
    }

    @Test
    @DisplayName("Should find and parse stored idempotency entry")
    void shouldFindStoredEntry() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        UUID notificationId = UUID.randomUUID();
        when(valueOperations.get("pigeon:idempotency:client-a:key-1")).thenReturn("hash-123:" + notificationId);

        Optional<IdempotencyStore.StoredIdempotency> result = adapter.find("client-a", IdempotencyKey.of("key-1"));

        assertThat(result).isPresent();
        assertThat(result.get().payloadHash()).isEqualTo("hash-123");
        assertThat(result.get().notificationId()).isEqualTo(notificationId);
    }

    @Test
    @DisplayName("Should return empty when key not found in Redis")
    void shouldReturnEmptyWhenNotFound() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("pigeon:idempotency:client-a:key-unknown")).thenReturn(null);

        Optional<IdempotencyStore.StoredIdempotency> result = adapter.find("client-a", IdempotencyKey.of("key-unknown"));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should gracefully handle Redis failure during find and return empty")
    void shouldHandleRedisFailureOnFind() {
        when(redisTemplate.opsForValue()).thenThrow(new RedisConnectionFailureException("Connection refused"));

        Optional<IdempotencyStore.StoredIdempotency> result = adapter.find("client-a", IdempotencyKey.of("key-1"));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Should save idempotency entry with TTL")
    void shouldSaveEntryWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        UUID notificationId = UUID.randomUUID();

        adapter.save("client-a", IdempotencyKey.of("key-1"), "hash-123", notificationId, Duration.ofHours(24));

        verify(valueOperations).set("pigeon:idempotency:client-a:key-1", "hash-123:" + notificationId, Duration.ofHours(24));
    }

    @Test
    @DisplayName("Should evict key on request")
    void shouldEvictKey() {
        adapter.evict("client-a", IdempotencyKey.of("key-1"));

        verify(redisTemplate).delete("pigeon:idempotency:client-a:key-1");
    }
}
