package io.github.andres.pigeon.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface OutboxRepository {
    void save(OutboxMessage message);
    List<OutboxMessage> lockNextBatch(int batchSize);
    void markPublished(UUID messageId, Instant publishedAt);
    int purgePublishedOlderThan(Instant threshold);
    long countUnpublished();

    record OutboxMessage(
            UUID id,
            UUID aggregateId,
            String eventType,
            String routingKey,
            String payload,
            Instant createdAt,
            Instant publishedAt,
            int publishAttempts
    ) {
        public static OutboxMessage create(UUID aggregateId, String eventType, String routingKey, String payload, Instant now) {
            return new OutboxMessage(UUID.randomUUID(), aggregateId, eventType, routingKey, payload, now, null, 0);
        }
    }
}
