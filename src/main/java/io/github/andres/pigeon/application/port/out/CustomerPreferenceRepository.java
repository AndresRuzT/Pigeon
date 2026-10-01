package io.github.andres.pigeon.application.port.out;

import io.github.andres.pigeon.domain.model.CustomerPreference;
import io.github.andres.pigeon.domain.vo.CustomerId;

import java.util.Optional;

public interface CustomerPreferenceRepository {
    Optional<CustomerPreference> findByCustomerId(CustomerId customerId);
    void save(CustomerPreference preference);
}
