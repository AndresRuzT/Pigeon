package io.github.andres.pigeon.infrastructure.adapter.out.persistence;

import io.github.andres.pigeon.application.port.out.OutboxRepository;
import io.github.andres.pigeon.infrastructure.adapter.out.persistence.entity.OutboxEventJpaEntity;
import io.github.andres.pigeon.infrastructure.adapter.out.persistence.repository.SpringDataOutboxEventRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class PostgresOutboxRepository implements OutboxRepository {

    private final SpringDataOutboxEventRepository repository;

    public PostgresOutboxRepository(SpringDataOutboxEventRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(OutboxMessage message) {
        OutboxEventJpaEntity entity = new OutboxEventJpaEntity();
        entity.setId(message.id());
        entity.setAggregateId(message.aggregateId());
        entity.setEventType(message.eventType());
        entity.setRoutingKey(message.routingKey());
        entity.setPayload(message.payload());
        entity.setCreatedAt(message.createdAt());
        entity.setPublishedAt(message.publishedAt());
        entity.setPublishAttempts(message.publishAttempts());
        repository.save(entity);
    }

    @Override
    public List<OutboxMessage> lockNextBatch(int batchSize) {
        return repository.lockNextBatch(batchSize).stream()
                .map(e -> new OutboxMessage(
                        e.getId(),
                        e.getAggregateId(),
                        e.getEventType(),
                        e.getRoutingKey(),
                        e.getPayload(),
                        e.getCreatedAt(),
                        e.getPublishedAt(),
                        e.getPublishAttempts()
                ))
                .toList();
    }

    @Override
    public void markPublished(UUID messageId, Instant publishedAt) {
        repository.findById(messageId).ifPresent(entity -> {
            entity.setPublishedAt(publishedAt);
            entity.setPublishAttempts(entity.getPublishAttempts() + 1);
            repository.save(entity);
        });
    }

    @Override
    public int purgePublishedOlderThan(Instant threshold) {
        return repository.purgePublishedOlderThan(threshold);
    }

    @Override
    public long countUnpublished() {
        return repository.countUnpublished();
    }
}
