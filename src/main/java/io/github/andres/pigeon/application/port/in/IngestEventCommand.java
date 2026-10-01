package io.github.andres.pigeon.application.port.in;

import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.domain.enums.Priority;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record IngestEventCommand(
        String clientId,
        String idempotencyKey,
        String payloadHash,
        String customerId,
        EventType eventType,
        String locale,
        Instant occurredAt,
        Map<String, Object> data
) {
    public IngestEventCommand {
        Objects.requireNonNull(clientId, "clientId cannot be null");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
        Objects.requireNonNull(payloadHash, "payloadHash cannot be null");
        Objects.requireNonNull(customerId, "customerId cannot be null");
        Objects.requireNonNull(eventType, "eventType cannot be null");
    }

    public record IngestResult(
            UUID notificationId,
            String status,
            Priority priority,
            boolean isReplay
    ) {}
}
