package io.github.andres.pigeon.domain.exception;

public class DuplicateEventException extends DomainException {
    public DuplicateEventException(String clientId, String idempotencyKey) {
        super("Idempotency key '%s' has already been used with a different payload for client '%s'"
                .formatted(idempotencyKey, clientId));
    }
}
