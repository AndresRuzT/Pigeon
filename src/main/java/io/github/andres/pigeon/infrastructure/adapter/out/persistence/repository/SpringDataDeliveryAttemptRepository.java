package io.github.andres.pigeon.infrastructure.adapter.out.persistence.repository;

import io.github.andres.pigeon.infrastructure.adapter.out.persistence.entity.DeliveryAttemptJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface SpringDataDeliveryAttemptRepository extends JpaRepository<DeliveryAttemptJpaEntity, UUID> {
}
