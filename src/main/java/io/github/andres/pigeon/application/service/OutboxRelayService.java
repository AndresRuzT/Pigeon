package io.github.andres.pigeon.application.service;

import io.github.andres.pigeon.application.port.out.ClockPort;
import io.github.andres.pigeon.application.port.out.MessagePublisher;
import io.github.andres.pigeon.application.port.out.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class OutboxRelayService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayService.class);
    private static final int BATCH_SIZE = 50;

    private final OutboxRepository outboxRepository;
    private final MessagePublisher messagePublisher;
    private final ClockPort clockPort;

    public OutboxRelayService(
            OutboxRepository outboxRepository,
            MessagePublisher messagePublisher,
            ClockPort clockPort
    ) {
        this.outboxRepository = outboxRepository;
        this.messagePublisher = messagePublisher;
        this.clockPort = clockPort;
    }

    @Scheduled(fixedDelayString = "${pigeon.outbox.poll-interval-ms:500}")
    @Transactional
    public void relayMessages() {
        List<OutboxRepository.OutboxMessage> pendingMessages = outboxRepository.lockNextBatch(BATCH_SIZE);
        if (pendingMessages.isEmpty()) {
            return;
        }

        for (OutboxRepository.OutboxMessage message : pendingMessages) {
            try {
                messagePublisher.publish(message.routingKey(), message.payload());
                outboxRepository.markPublished(message.id(), clockPort.now());
                log.debug("Successfully relayed outbox event {} to routing key {}", message.id(), message.routingKey());
            } catch (Exception e) {
                log.error("Failed to relay outbox event {}: {}", message.id(), e.getMessage());
                // Will be retried on next poll interval
            }
        }
    }
}
