package io.github.andres.pigeon.application.service;

import io.github.andres.pigeon.application.port.in.IngestEventCommand;
import io.github.andres.pigeon.application.port.in.IngestEventUseCase;
import io.github.andres.pigeon.application.port.out.AuditLogPort;
import io.github.andres.pigeon.application.port.out.ClockPort;
import io.github.andres.pigeon.application.port.out.IdempotencyStore;
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

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Service
public class IngestEventService implements IngestEventUseCase {

    private static final Duration IDEMPOTENCY_TTL = Duration.ofHours(24);

    private final NotificationRepository notificationRepository;
    private final OutboxRepository outboxRepository;
    private final AuditLogPort auditLogPort;
    private final ClockPort clockPort;
    private final IdempotencyStore idempotencyStore;

    public IngestEventService(
            NotificationRepository notificationRepository,
            OutboxRepository outboxRepository,
            AuditLogPort auditLogPort,
            ClockPort clockPort,
            IdempotencyStore idempotencyStore
    ) {
        this.notificationRepository = notificationRepository;
        this.outboxRepository = outboxRepository;
        this.auditLogPort = auditLogPort;
        this.clockPort = clockPort;
        this.idempotencyStore = idempotencyStore;
    }

    @Override
    public IngestEventCommand.IngestResult ingest(IngestEventCommand command) {
        IdempotencyKey key = IdempotencyKey.of(command.idempotencyKey());

        // 1. Fast-Path: Atomic acquire in Redis via SET NX
        IdempotencyStore.AcquisitionResult acquisition = idempotencyStore.acquireOrFind(
                command.clientId(),
                key,
                command.payloadHash(),
                IDEMPOTENCY_TTL
        );

        if (acquisition.result() == IdempotencyStore.LockResult.EXISTS) {
            return resolveExisting(command, key, acquisition.existing().orElse(null));
        }

        // 2. Either ACQUIRED or STORE_UNAVAILABLE (Redis down): Proceed to DB insert
        try {
            return createAndSaveNotification(command, key);
        } catch (Exception ex) {
            // Check if concurrent thread committed or unique constraint was violated
            Optional<Notification> existingOpt = notificationRepository.findByClientIdAndIdempotencyKey(command.clientId(), key);
            if (existingOpt.isPresent()) {
                Notification existing = existingOpt.get();
                if (!existing.getPayloadHash().equals(command.payloadHash())) {
                    throw new DuplicateEventException(command.clientId(), command.idempotencyKey());
                }
                idempotencyStore.save(command.clientId(), key, existing.getPayloadHash(), existing.getId(), IDEMPOTENCY_TTL);
                return new IngestEventCommand.IngestResult(
                        existing.getId(),
                        existing.getStatus().name(),
                        existing.getPriority(),
                        true
                );
            }
            // Release Redis lock key on transaction failure so producer can retry
            idempotencyStore.evict(command.clientId(), key);
            throw ex;
        }
    }

    private IngestEventCommand.IngestResult resolveExisting(
            IngestEventCommand command,
            IdempotencyKey key,
            IdempotencyStore.StoredIdempotency stored
    ) {
        if (stored != null) {
            if (!stored.payloadHash().equals(command.payloadHash())) {
                throw new DuplicateEventException(command.clientId(), command.idempotencyKey());
            }
            if (stored.notificationId() != null) {
                Optional<Notification> existingOpt = notificationRepository.findById(stored.notificationId());
                if (existingOpt.isPresent()) {
                    Notification existing = existingOpt.get();
                    return new IngestEventCommand.IngestResult(
                            existing.getId(),
                            existing.getStatus().name(),
                            existing.getPriority(),
                            true
                    );
                }
            }
        }

        // If in-flight, await concurrent transaction commit briefly
        for (int i = 0; i < 20; i++) {
            Optional<Notification> existingOpt = notificationRepository.findByClientIdAndIdempotencyKey(command.clientId(), key);
            if (existingOpt.isPresent()) {
                Notification existing = existingOpt.get();
                if (!existing.getPayloadHash().equals(command.payloadHash())) {
                    throw new DuplicateEventException(command.clientId(), command.idempotencyKey());
                }
                idempotencyStore.save(command.clientId(), key, existing.getPayloadHash(), existing.getId(), IDEMPOTENCY_TTL);
                return new IngestEventCommand.IngestResult(
                        existing.getId(),
                        existing.getStatus().name(),
                        existing.getPriority(),
                        true
                );
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        Optional<Notification> finalCheck = notificationRepository.findByClientIdAndIdempotencyKey(command.clientId(), key);
        if (finalCheck.isPresent()) {
            Notification existing = finalCheck.get();
            return new IngestEventCommand.IngestResult(
                    existing.getId(),
                    existing.getStatus().name(),
                    existing.getPriority(),
                    true
            );
        }
        throw new DuplicateEventException(command.clientId(), command.idempotencyKey());
    }

    @Transactional
    public IngestEventCommand.IngestResult createAndSaveNotification(IngestEventCommand command, IdempotencyKey key) {
        Optional<Notification> existing = notificationRepository.findByClientIdAndIdempotencyKey(command.clientId(), key);
        if (existing.isPresent()) {
            Notification notification = existing.get();
            if (!notification.getPayloadHash().equals(command.payloadHash())) {
                throw new DuplicateEventException(command.clientId(), command.idempotencyKey());
            }
            idempotencyStore.save(command.clientId(), key, notification.getPayloadHash(), notification.getId(), IDEMPOTENCY_TTL);
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

        idempotencyStore.save(command.clientId(), key, saved.getPayloadHash(), saved.getId(), IDEMPOTENCY_TTL);

        return new IngestEventCommand.IngestResult(
                saved.getId(),
                saved.getStatus().name(),
                saved.getPriority(),
                false
        );
    }
}
