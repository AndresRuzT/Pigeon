package io.github.andres.pigeon.domain.vo;

import java.util.Objects;

public record CustomerId(String value) {
    public CustomerId {
        Objects.requireNonNull(value, "CustomerId cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("CustomerId cannot be blank");
        }
    }

    public static CustomerId of(String value) {
        return new CustomerId(value);
    }
}
