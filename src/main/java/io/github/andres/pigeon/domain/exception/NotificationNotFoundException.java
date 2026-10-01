package io.github.andres.pigeon.domain.exception;

import java.util.UUID;

public class NotificationNotFoundException extends DomainException {
    public NotificationNotFoundException(UUID id) {
        super("Notification not found with id: %s".formatted(id));
    }
}
