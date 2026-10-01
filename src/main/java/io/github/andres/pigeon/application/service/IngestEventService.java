package io.github.andres.pigeon.application.service;

import io.github.andres.pigeon.application.port.in.IngestEventCommand;
import io.github.andres.pigeon.application.port.in.IngestEventUseCase;
import io.github.andres.pigeon.application.port.out.AuditLogPort;
import io.github.andres.pigeon.application.port.out.ClockPort;
import io.github.andres.pigeon.application.port.out.NotificationRepository;
import io.github.andres.pigeon.application.port.out.OutboxRepository;
import io.github.andres.pigeon.domain.enums.NotificationStatus;
import io.github.andres.pigeon.domain.exception.DuplicateEventException;
import io.github.andres.pigeon.domain.model.AuditRecord;
import io.github.andres.pigeon.domain.model.Notification;
import io.github.andres.pigeon.domain.vo.CustomerId;
import io.github.andres.pigeon.domain.vo.IdempotencyKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

@Service
public class IngestEventService implements IngestEventUseCase {

    private final NotificationRepository notificationRepository;
    private final OutboxRepository outboxRepository;
    private final AuditLogPort auditLogPort;
    private final ClockPort clockPort;

    public IngestEventService(
            NotificationRepository notificationRepository,
            OutboxRepository outboxRepository,
            AuditLogPort auditLogPort,
            ClockPort clockPort
    ) {
        this.notificationRepository = notificationRepository;
        this.outboxRepository = outboxRepository;
        this.auditLogPort = auditLogPort;
        this.clockPort = clockPort;
    }

    @Override
    @Transactional
    public IngestEventCommand.IngestResult ingest(IngestEventCommand command) {
        IdempotencyKey key = IdempotencyKey.of(command.idempotencyKey());
        Optional<Notification> existing = notificationRepository.findByClientIdAndIdempotencyKey(command.clientId(), key);

        if (existing.isPresent()) {
            Notification notification = existing.get();
            if (!notification.getPayloadHash().equals(command.payloadHash())) {
                throw new DuplicateEventException(command.clientId(), command.idempotencyKey());
            }
            return new IngestEventCommand.IngestResult(
                    notification.getId(),
                    notification.getStatus().name(),
                    notification.getPriority(),
                    true
            );
        }

        Instant now = clockPort.now();
        Notification notification = Notification.createPending(
                command.clientId(),
                key,
                command.payloadHash(),
                CustomerId.of(command.customerId()),
                command.eventType(),
                command.locale(),
                command.data(),
                now
        );

        Notification saved = notificationRepository.save(notification);

        // Record initial PENDING audit entry
        AuditRecord initialAudit = AuditRecord.create(
                saved.getId(),
                command.customerId(),
                command.clientId(),
                "NOTIFICATION_ACCEPTED",
                null,
                NotificationStatus.PENDING,
                null,
                "Event accepted for delivery",
                null,
                now
        );
        auditLogPort.append(initialAudit);

        // Create Outbox message envelope (zero PII in message payload)
        String routingKey = saved.getPriority().name().toLowerCase();
        String envelopePayload = """
                {"notificationId":"%s","eventType":"%s","priority":"%s","attempt":1,"schemaVersion":1}
                """.formatted(saved.getId(), saved.getEventType(), saved.getPriority()).trim();

        OutboxRepository.OutboxMessage outboxMessage = OutboxRepository.OutboxMessage.create(
                saved.getId(),
                saved.getEventType().name(),
                routingKey,
                envelopePayload,
                now
        );
        outboxRepository.save(outboxMessage);

        return new IngestEventCommand.IngestResult(
                saved.getId(),
                saved.getStatus().name(),
                saved.getPriority(),
                false
        );
    }
}
