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
        String prevHash = record.prevHash();
        if (prevHash == null) {
            String priorHash = repository.findTopByNotificationIdOrderByOccurredAtDesc(record.notificationId())
                    .map(AuditLogJpaEntity::getPrevHash)
                    .orElse("0000000000000000000000000000000000000000000000000000000000000000");
            prevHash = computeSha256(priorHash, record);
        }

        AuditRecord chainedRecord = new AuditRecord(
                record.id(),
                record.occurredAt(),
                record.notificationId(),
                record.customerId(),
                record.actor(),
                record.action(),
                record.fromStatus(),
                record.toStatus(),
                record.channel(),
                record.templateVersion(),
                record.reason(),
                record.correlationId(),
                prevHash
        );

        AuditLogJpaEntity entity = mapper.toJpaEntity(chainedRecord);
        repository.save(entity);
    }

    private String computeSha256(String priorHash, AuditRecord record) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            String data = priorHash + "|" + record.id() + "|" + record.occurredAt() + "|"
                    + record.actor() + "|" + record.action() + "|" + record.toStatus() + "|"
                    + (record.channel() != null ? record.channel().name() : "") + "|"
                    + (record.reason() != null ? record.reason() : "");
            byte[] hash = digest.digest(data.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    @Override
    public List<AuditRecord> findByNotificationId(UUID notificationId) {
        return repository.findByNotificationIdOrderByOccurredAtAsc(notificationId).stream()
                .map(mapper::toDomain)
                .toList();
    }
}
