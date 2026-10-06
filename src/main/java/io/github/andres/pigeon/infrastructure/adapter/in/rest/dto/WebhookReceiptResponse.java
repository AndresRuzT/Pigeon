package io.github.andres.pigeon.infrastructure.adapter.in.rest.dto;

import java.util.UUID;

public record WebhookReceiptResponse(
        UUID notificationId,
        String status,
        boolean processed
) {}
