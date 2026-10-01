package io.github.andres.pigeon.infrastructure.adapter.out.persistence;

import io.github.andres.pigeon.application.port.out.AuditLogPort;
import io.github.andres.pigeon.domain.model.AuditRecord;
import io.github.andres.pigeon.infrastructure.adapter.out.persistence.entity.AuditLogJpaEntity;
import io.github.andres.pigeon.infrastructure.adapter.out.persistence.mapper.PersistenceMapper;
import io.github.andres.pigeon.infrastructure.adapter.out.persistence.repository.SpringDataAuditLogRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class PostgresAuditLogAdapter implements AuditLogPort {

    private final SpringDataAuditLogRepository repository;
    private final PersistenceMapper mapper;

    public PostgresAuditLogAdapter(SpringDataAuditLogRepository repository, PersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public void append(AuditRecord record) {
        AuditLogJpaEntity entity = mapper.toJpaEntity(record);
        repository.save(entity);
    }

    @Override
    public List<AuditRecord> findByNotificationId(UUID notificationId) {
        return repository.findByNotificationIdOrderByOccurredAtAsc(notificationId).stream()
                .map(mapper::toDomain)
                .toList();
    }
}
