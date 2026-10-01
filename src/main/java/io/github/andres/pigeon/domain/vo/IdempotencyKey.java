package io.github.andres.pigeon.domain.vo;

import java.util.Objects;

public record IdempotencyKey(String value) {
    public IdempotencyKey {
        Objects.requireNonNull(value, "IdempotencyKey cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("IdempotencyKey cannot be blank");
        }
    }

    public static IdempotencyKey of(String value) {
        return new IdempotencyKey(value);
    }
}
