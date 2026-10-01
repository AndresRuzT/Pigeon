package io.github.andres.pigeon.domain.model;

import io.github.andres.pigeon.domain.vo.CustomerId;

import java.util.Objects;

public record CustomerContact(
        CustomerId customerId,
        String email,
        String phone,
        String pushToken
) {
    public CustomerContact {
        Objects.requireNonNull(customerId, "customerId cannot be null");
    }

    public static CustomerContact of(String customerId, String email, String phone, String pushToken) {
        return new CustomerContact(CustomerId.of(customerId), email, phone, pushToken);
    }
}
