package io.github.andres.pigeon.domain.exception;

public class CustomerOptedOutException extends DomainException {

    public CustomerOptedOutException(String message) {
        super(message);
    }
}
