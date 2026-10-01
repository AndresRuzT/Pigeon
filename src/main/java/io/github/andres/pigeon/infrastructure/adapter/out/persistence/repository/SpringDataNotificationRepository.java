package io.github.andres.pigeon.infrastructure.adapter.out.persistence.repository;

import io.github.andres.pigeon.infrastructure.adapter.out.persistence.entity.NotificationJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SpringDataNotificationRepository extends JpaRepository<NotificationJpaEntity, UUID> {
    Optional<NotificationJpaEntity> findByClientIdAndIdempotencyKey(String clientId, String idempotencyKey);
}
