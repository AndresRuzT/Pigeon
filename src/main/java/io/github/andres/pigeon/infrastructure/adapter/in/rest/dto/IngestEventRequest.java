package io.github.andres.pigeon.infrastructure.adapter.in.rest.dto;

import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.infrastructure.adapter.in.rest.validation.NoSensitiveData;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;

import java.time.Instant;
import java.util.Map;

@NoSensitiveData
public record IngestEventRequest(
        @NotNull(message = "eventType is required")
        EventType eventType,

        @NotBlank(message = "customerId is required")
        String customerId,

        @NotNull(message = "occurredAt is required")
        @PastOrPresent(message = "occurredAt cannot be in the future")
        Instant occurredAt,

        String locale,

        @NotNull(message = "data object is required")
        Map<String, Object> data
) {}
