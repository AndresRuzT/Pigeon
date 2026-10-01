package io.github.andres.pigeon.infrastructure.adapter.in.rest.dto;

import io.github.andres.pigeon.domain.enums.FailureReason;
import io.github.andres.pigeon.domain.enums.NotificationStatus;
import io.github.andres.pigeon.domain.enums.Priority;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record NotificationResponse(
        UUID id,
        String customerId,
        String eventType,
        Priority priority,
        String locale,
        NotificationStatus status,
        FailureReason failureReason,
        Map<String, Object> data,
        Instant createdAt,
        Instant updatedAt,
        List<DeliveryAttemptResponse> attempts,
        List<AuditRecordResponse> auditTrail
) {
    public record DeliveryAttemptResponse(
            UUID id,
            String channel,
            int attemptNo,
            String outcome,
            String providerRef,
            String errorCode,
            long latencyMs,
            Instant createdAt
    ) {}

    public record AuditRecordResponse(
            UUID id,
            Instant occurredAt,
            String actor,
            String action,
            String fromStatus,
            String toStatus,
            String channel,
            String reason,
            String correlationId
    ) {}
}
