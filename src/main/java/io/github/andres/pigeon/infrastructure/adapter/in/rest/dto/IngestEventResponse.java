package io.github.andres.pigeon.infrastructure.adapter.in.rest.dto;

import io.github.andres.pigeon.domain.enums.Priority;

import java.util.UUID;

public record IngestEventResponse(
        UUID notificationId,
        String status,
        Priority priority
) {}
