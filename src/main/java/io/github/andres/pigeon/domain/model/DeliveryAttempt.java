package io.github.andres.pigeon.domain.model;

import io.github.andres.pigeon.domain.enums.Channel;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record DeliveryAttempt(
        UUID id,
        UUID notificationId,
        Channel channel,
        int attemptNo,
        String outcome,
        String providerRef,
        String errorCode,
        long latencyMs,
        Instant createdAt
) {
    public DeliveryAttempt {
        Objects.requireNonNull(id, "id cannot be null");
        Objects.requireNonNull(notificationId, "notificationId cannot be null");
        Objects.requireNonNull(channel, "channel cannot be null");
        Objects.requireNonNull(outcome, "outcome cannot be null");
        Objects.requireNonNull(createdAt, "createdAt cannot be null");
    }

    public static DeliveryAttempt success(UUID notificationId, Channel channel, int attemptNo, String providerRef, long latencyMs, Instant now) {
        return new DeliveryAttempt(UUID.randomUUID(), notificationId, channel, attemptNo, "SUCCESS", providerRef, null, latencyMs, now);
    }

    public static DeliveryAttempt failure(UUID notificationId, Channel channel, int attemptNo, String errorCode, long latencyMs, Instant now) {
        return new DeliveryAttempt(UUID.randomUUID(), notificationId, channel, attemptNo, "FAILURE", null, errorCode, latencyMs, now);
    }
}
