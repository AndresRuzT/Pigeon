package io.github.andres.pigeon.infrastructure.adapter.in.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

public record WebhookReceiptRequest(
        @NotNull(message = "notificationId is required")
        UUID notificationId,

        String providerRef,

        @NotBlank(message = "status is required (DELIVERED or FAILED)")
        String status,

        String errorCode,
        String reason,
        Instant occurredAt
) {}
