package io.github.andres.pigeon.infrastructure.adapter.out.persistence;

import io.github.andres.pigeon.application.port.out.NotificationRepository;
import io.github.andres.pigeon.domain.model.Notification;
import io.github.andres.pigeon.domain.vo.IdempotencyKey;
import io.github.andres.pigeon.infrastructure.adapter.out.persistence.entity.NotificationJpaEntity;
import io.github.andres.pigeon.infrastructure.adapter.out.persistence.mapper.PersistenceMapper;
import io.github.andres.pigeon.infrastructure.adapter.out.persistence.repository.SpringDataNotificationRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class PostgresNotificationRepository implements NotificationRepository {

    private final SpringDataNotificationRepository repository;
    private final PersistenceMapper mapper;

    public PostgresNotificationRepository(SpringDataNotificationRepository repository, PersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public Notification save(Notification notification) {
        NotificationJpaEntity entity = mapper.toJpaEntity(notification);
        NotificationJpaEntity saved = repository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<Notification> findById(UUID id) {
        return repository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Optional<Notification> findByClientIdAndIdempotencyKey(String clientId, IdempotencyKey key) {
        return repository.findByClientIdAndIdempotencyKey(clientId, key.value())
                .map(mapper::toDomain);
    }
}
