package io.github.andres.pigeon.application.port.out;

import io.github.andres.pigeon.domain.model.Notification;
import io.github.andres.pigeon.domain.vo.IdempotencyKey;

import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository {
    Notification save(Notification notification);
    Optional<Notification> findById(UUID id);
    Optional<Notification> findByClientIdAndIdempotencyKey(String clientId, IdempotencyKey key);
    java.util.List<Notification> findDueDeferred(java.time.Instant now, int limit);
}
