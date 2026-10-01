package io.github.andres.pigeon.infrastructure.adapter.out.persistence;

import io.github.andres.pigeon.application.port.out.ContactRepository;
import io.github.andres.pigeon.domain.model.CustomerContact;
import io.github.andres.pigeon.domain.vo.CustomerId;
import io.github.andres.pigeon.infrastructure.adapter.out.persistence.entity.CustomerContactJpaEntity;
import io.github.andres.pigeon.infrastructure.adapter.out.persistence.mapper.PersistenceMapper;
import io.github.andres.pigeon.infrastructure.adapter.out.persistence.repository.SpringDataCustomerContactRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class PostgresContactRepository implements ContactRepository {

    private final SpringDataCustomerContactRepository repository;
    private final PersistenceMapper mapper;

    public PostgresContactRepository(SpringDataCustomerContactRepository repository, PersistenceMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public Optional<CustomerContact> findByCustomerId(CustomerId customerId) {
        return repository.findById(customerId.value()).map(mapper::toDomain);
    }

    @Override
    public void save(CustomerContact contact) {
        CustomerContactJpaEntity entity = mapper.toJpaEntity(contact);
        repository.save(entity);
    }
}
