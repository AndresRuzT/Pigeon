package io.github.andres.pigeon.domain.model;

import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.enums.NotificationStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AuditRecord(
        UUID id,
        Instant occurredAt,
        UUID notificationId,
        String customerId,
        String actor,
        String action,
        NotificationStatus fromStatus,
        NotificationStatus toStatus,
        Channel channel,
        String templateVersion,
        String reason,
        String correlationId,
        String prevHash
) {
    public AuditRecord {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(occurredAt, "occurredAt cannot be null");
        Objects.requireNonNull(notificationId, "notificationId cannot be null");
        Objects.requireNonNull(actor, "actor cannot be null");
        Objects.requireNonNull(action, "action cannot be null");
        Objects.requireNonNull(toStatus, "toStatus cannot be null");
    }

    public static AuditRecord create(
            UUID notificationId,
            String customerId,
            String actor,
            String action,
            NotificationStatus fromStatus,
            NotificationStatus toStatus,
            Channel channel,
            String reason,
            String correlationId,
            Instant occurredAt
    ) {
        return new AuditRecord(
                UUID.randomUUID(),
                occurredAt,
                notificationId,
                customerId,
                actor,
                action,
                fromStatus,
                toStatus,
                channel,
                null,
                reason,
                correlationId,
                null
        );
    }
}
