package io.github.andres.pigeon.infrastructure.adapter.out.persistence.repository;

import io.github.andres.pigeon.infrastructure.adapter.out.persistence.entity.CustomerContactJpaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SpringDataCustomerContactRepository extends JpaRepository<CustomerContactJpaEntity, String> {
}
