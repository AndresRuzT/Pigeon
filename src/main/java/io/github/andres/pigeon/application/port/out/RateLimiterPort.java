package io.github.andres.pigeon.application.port.out;

import io.github.andres.pigeon.domain.enums.EventType;

public interface RateLimiterPort {
    boolean isAllowed(String customerId, EventType eventType);
}
