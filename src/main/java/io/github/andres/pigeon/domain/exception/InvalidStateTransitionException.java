package io.github.andres.pigeon.domain.exception;

import io.github.andres.pigeon.domain.enums.NotificationStatus;

public class InvalidStateTransitionException extends DomainException {
    public InvalidStateTransitionException(NotificationStatus from, NotificationStatus to) {
        super("Illegal state transition from %s to %s".formatted(from, to));
    }
}
