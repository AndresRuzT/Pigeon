package io.github.andres.pigeon.application.port.out;

import io.github.andres.pigeon.domain.model.CustomerContact;
import io.github.andres.pigeon.domain.vo.CustomerId;

import java.util.Optional;

public interface ContactRepository {
    Optional<CustomerContact> findByCustomerId(CustomerId customerId);
    void save(CustomerContact contact);
}
