package io.github.andres.pigeon.domain.exception;

public class SensitiveDataException extends DomainException {
    public SensitiveDataException(String message) {
        super(message);
    }
}
