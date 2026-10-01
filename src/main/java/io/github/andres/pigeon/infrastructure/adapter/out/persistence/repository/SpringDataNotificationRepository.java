package io.github.andres.pigeon.infrastructure.adapter.out.persistence.repository;

import io.github.andres.pigeon.infrastructure.adapter.out.persistence.entity.NotificationJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SpringDataNotificationRepository extends JpaRepository<NotificationJpaEntity, UUID> {
    Optional<NotificationJpaEntity> findByClientIdAndIdempotencyKey(String clientId, String idempotencyKey);

    @org.springframework.data.jpa.repository.Query(
            value = "SELECT * FROM notification WHERE status = 'DEFERRED' AND scheduled_at <= :now ORDER BY scheduled_at ASC LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true
    )
    java.util.List<NotificationJpaEntity> findDueDeferred(
            @org.springframework.data.repository.query.Param("now") java.time.Instant now,
            @org.springframework.data.repository.query.Param("limit") int limit
    );
}
