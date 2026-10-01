package io.github.andres.pigeon.infrastructure.adapter.out.persistence;

import io.github.andres.pigeon.application.port.out.CustomerPreferenceRepository;
import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.model.CustomerPreference;
import io.github.andres.pigeon.domain.vo.CustomerId;
import io.github.andres.pigeon.infrastructure.adapter.out.persistence.entity.CustomerPreferenceJpaEntity;
import io.github.andres.pigeon.infrastructure.adapter.out.persistence.repository.SpringDataCustomerPreferenceRepository;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class CustomerPreferencePersistenceAdapter implements CustomerPreferenceRepository {

    private final SpringDataCustomerPreferenceRepository repository;

    public CustomerPreferencePersistenceAdapter(SpringDataCustomerPreferenceRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<CustomerPreference> findByCustomerId(CustomerId customerId) {
        return repository.findById(customerId.value()).map(this::toDomain);
    }

    @Override
    public void save(CustomerPreference preference) {
        repository.save(toEntity(preference));
    }

    private CustomerPreference toDomain(CustomerPreferenceJpaEntity entity) {
        List<Channel> allowedChannels = parseChannels(entity.getAllowedChannels());
        List<Channel> order = parseChannels(entity.getPreferredChannelOrder());
        Set<String> optOuts = parseOptOuts(entity.getOptOutCategories());
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(entity.getTimeZone());
        } catch (Exception e) {
            zoneId = ZoneId.of("UTC");
        }

        return new CustomerPreference(
                CustomerId.of(entity.getCustomerId()),
                allowedChannels,
                order,
                optOuts,
                zoneId,
                entity.isQuietHoursEnabled(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private CustomerPreferenceJpaEntity toEntity(CustomerPreference domain) {
        CustomerPreferenceJpaEntity entity = new CustomerPreferenceJpaEntity();
        entity.setCustomerId(domain.getCustomerId().value());
        entity.setAllowedChannels(domain.getAllowedChannels().stream().map(Enum::name).collect(Collectors.joining(",")));
        entity.setPreferredChannelOrder(domain.getPreferredChannelOrder().stream().map(Enum::name).collect(Collectors.joining(",")));
        entity.setOptOutCategories(String.join(",", domain.getOptOutCategories()));
        entity.setTimeZone(domain.getTimeZone().getId());
        entity.setQuietHoursEnabled(domain.isQuietHoursEnabled());
        entity.setCreatedAt(domain.getCreatedAt());
        entity.setUpdatedAt(domain.getUpdatedAt());
        return entity;
    }

    private List<Channel> parseChannels(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of(Channel.PUSH, Channel.SMS, Channel.EMAIL);
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Channel::valueOf)
                .collect(Collectors.toList());
    }

    private Set<String> parseOptOuts(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }
}
