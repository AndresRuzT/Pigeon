package io.github.andres.pigeon.infrastructure.adapter.out.persistence.mapper;

import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.domain.model.AuditRecord;
import io.github.andres.pigeon.domain.model.CustomerContact;
import io.github.andres.pigeon.domain.model.DeliveryAttempt;
import io.github.andres.pigeon.domain.model.Notification;
import io.github.andres.pigeon.domain.vo.CustomerId;
import io.github.andres.pigeon.domain.vo.IdempotencyKey;
import io.github.andres.pigeon.infrastructure.adapter.out.persistence.entity.AuditLogJpaEntity;
import io.github.andres.pigeon.infrastructure.adapter.out.persistence.entity.CustomerContactJpaEntity;
import io.github.andres.pigeon.infrastructure.adapter.out.persistence.entity.DeliveryAttemptJpaEntity;
import io.github.andres.pigeon.infrastructure.adapter.out.persistence.entity.NotificationJpaEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class PersistenceMapper {

    public NotificationJpaEntity toJpaEntity(Notification domain) {
        NotificationJpaEntity entity = new NotificationJpaEntity();
        entity.setId(domain.getId());
        entity.setClientId(domain.getClientId());
        entity.setIdempotencyKey(domain.getIdempotencyKey().value());
        entity.setPayloadHash(domain.getPayloadHash());
        entity.setCustomerId(domain.getCustomerId().value());
        entity.setEventType(domain.getEventType().name());
        entity.setPriority(domain.getPriority());
        entity.setLocale(domain.getLocale());
        entity.setStatus(domain.getStatus());
        entity.setFailureReason(domain.getFailureReason());
        entity.setTemplateId(domain.getTemplateId());
        entity.setTemplateVersion(domain.getTemplateVersion());
        entity.setData(domain.getData());
        entity.setScheduledAt(domain.getScheduledAt());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        entity.setVersion(domain.getVersion());

        List<DeliveryAttemptJpaEntity> attemptEntities = new ArrayList<>();
        for (DeliveryAttempt attempt : domain.getAttempts()) {
            DeliveryAttemptJpaEntity attemptEntity = toJpaEntity(attempt, entity);
            attemptEntities.add(attemptEntity);
        }
        entity.setAttempts(attemptEntities);

        return entity;
    }

    public Notification toDomain(NotificationJpaEntity entity) {
        List<DeliveryAttempt> attempts = entity.getAttempts().stream()
                .map(this::toDomain)
                .toList();

        return new Notification(
                entity.getId(),
                entity.getClientId(),
                IdempotencyKey.of(entity.getIdempotencyKey()),
                entity.getPayloadHash(),
                CustomerId.of(entity.getCustomerId()),
                EventType.valueOf(entity.getEventType()),
                entity.getPriority(),
                entity.getLocale(),
                entity.getStatus(),
                entity.getFailureReason(),
                entity.getTemplateId(),
                entity.getTemplateVersion(),
                entity.getData(),
                entity.getScheduledAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion(),
                attempts
        );
    }

    public DeliveryAttemptJpaEntity toJpaEntity(DeliveryAttempt attempt, NotificationJpaEntity parent) {
        DeliveryAttemptJpaEntity entity = new DeliveryAttemptJpaEntity();
        entity.setId(attempt.id());
        entity.setNotification(parent);
        entity.setChannel(attempt.channel());
        entity.setAttemptNo(attempt.attemptNo());
        entity.setOutcome(attempt.outcome());
        entity.setProviderRef(attempt.providerRef());
        entity.setErrorCode(attempt.errorCode());
        entity.setLatencyMs(attempt.latencyMs());
        entity.setCreatedAt(attempt.createdAt());
        return entity;
    }

    public DeliveryAttempt toDomain(DeliveryAttemptJpaEntity entity) {
        return new DeliveryAttempt(
                entity.getId(),
                entity.getNotification().getId(),
                entity.getChannel(),
                entity.getAttemptNo(),
                entity.getOutcome(),
                entity.getProviderRef(),
                entity.getErrorCode(),
                entity.getLatencyMs(),
                entity.getCreatedAt()
        );
    }

    public AuditLogJpaEntity toJpaEntity(AuditRecord record) {
        AuditLogJpaEntity entity = new AuditLogJpaEntity();
        entity.setId(record.id());
        entity.setOccurredAt(record.occurredAt());
        entity.setNotificationId(record.notificationId());
        entity.setCustomerId(record.customerId());
        entity.setActor(record.actor());
        entity.setAction(record.action());
        entity.setFromStatus(record.fromStatus());
        entity.setToStatus(record.toStatus());
        entity.setChannel(record.channel());
        entity.setTemplateVersion(record.templateVersion());
        entity.setReason(record.reason());
        entity.setCorrelationId(record.correlationId());
        entity.setPrevHash(record.prevHash());
        return entity;
    }

    public AuditRecord toDomain(AuditLogJpaEntity entity) {
        return new AuditRecord(
                entity.getId(),
                entity.getOccurredAt(),
                entity.getNotificationId(),
                entity.getCustomerId(),
                entity.getActor(),
                entity.getAction(),
                entity.getFromStatus(),
                entity.getToStatus(),
                entity.getChannel(),
                entity.getTemplateVersion(),
                entity.getReason(),
                entity.getCorrelationId(),
                entity.getPrevHash()
        );
    }

    public CustomerContact toDomain(CustomerContactJpaEntity entity) {
        return CustomerContact.of(
                entity.getCustomerId(),
                entity.getEmail(),
                entity.getPhone(),
                entity.getPushToken()
        );
    }

    public CustomerContactJpaEntity toJpaEntity(CustomerContact domain) {
        CustomerContactJpaEntity entity = new CustomerContactJpaEntity();
        entity.setCustomerId(domain.customerId().value());
        entity.setEmail(domain.email());
        entity.setPhone(domain.phone());
        entity.setPushToken(domain.pushToken());
        entity.setCreatedAt(java.time.Instant.now());
        return entity;
    }
}
