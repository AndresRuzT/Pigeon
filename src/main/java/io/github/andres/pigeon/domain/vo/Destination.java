package io.github.andres.pigeon.domain.vo;

import java.util.Objects;

public record Destination(String value) {
    public Destination {
        Objects.requireNonNull(value, "Destination cannot be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Destination cannot be blank");
        }
    }

    public static Destination of(String value) {
        return new Destination(value);
    }
}
