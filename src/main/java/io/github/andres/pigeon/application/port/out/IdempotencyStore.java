package io.github.andres.pigeon.application.port.out;

import io.github.andres.pigeon.domain.vo.IdempotencyKey;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

public interface IdempotencyStore {

    record StoredIdempotency(String payloadHash, UUID notificationId) {}

    enum LockResult {
        ACQUIRED,
        EXISTS,
        STORE_UNAVAILABLE
    }

    record AcquisitionResult(LockResult result, Optional<StoredIdempotency> existing) {}

    AcquisitionResult acquireOrFind(String clientId, IdempotencyKey key, String payloadHash, Duration ttl);

    Optional<StoredIdempotency> find(String clientId, IdempotencyKey key);

    void save(String clientId, IdempotencyKey key, String payloadHash, UUID notificationId, Duration ttl);

    void evict(String clientId, IdempotencyKey key);
}
