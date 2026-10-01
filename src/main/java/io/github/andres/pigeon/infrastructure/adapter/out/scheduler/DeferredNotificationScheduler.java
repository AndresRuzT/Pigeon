package io.github.andres.pigeon.infrastructure.adapter.out.scheduler;

import io.github.andres.pigeon.application.port.out.AuditLogPort;
import io.github.andres.pigeon.application.port.out.ClockPort;
import io.github.andres.pigeon.application.port.out.NotificationRepository;
import io.github.andres.pigeon.application.port.out.OutboxRepository;
import io.github.andres.pigeon.domain.model.AuditRecord;
import io.github.andres.pigeon.domain.model.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Periodically polls for deferred notifications whose quiet-hours window has elapsed
 * and transitions them to PENDING, publishing them to the outbox for delivery.
 */
@Component
public class DeferredNotificationScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeferredNotificationScheduler.class);
    private static final int BATCH_SIZE = 50;

    private final NotificationRepository notificationRepository;
    private final OutboxRepository outboxRepository;
    private final AuditLogPort auditLogPort;
    private final ClockPort clockPort;

    public DeferredNotificationScheduler(
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

    @Scheduled(fixedDelayString = "${pigeon.scheduler.deferred-poll-ms:5000}")
    @Transactional
    public void resumeDeferredNotifications() {
        Instant now = clockPort.now();
        List<Notification> dueList = notificationRepository.findDueDeferred(now, BATCH_SIZE);

        if (dueList.isEmpty()) {
            return;
        }

        log.info("Found {} deferred notifications ready to resume after quiet hours.", dueList.size());

        for (Notification notification : dueList) {
            AuditRecord resumeAudit = notification.markPendingFromDeferred("deferred-scheduler", null, now);
            notificationRepository.save(notification);
            auditLogPort.append(resumeAudit);

            String routingKey = notification.getPriority().name().toLowerCase();
            String envelopePayload = """
                    {"notificationId":"%s","eventType":"%s","priority":"%s","attempt":1,"schemaVersion":1}
                    """.formatted(notification.getId(), notification.getEventType(), notification.getPriority()).trim();

            OutboxRepository.OutboxMessage outboxMessage = OutboxRepository.OutboxMessage.create(
                    notification.getId(),
                    notification.getEventType().name(),
                    routingKey,
                    envelopePayload,
                    now
            );
            outboxRepository.save(outboxMessage);

            log.info("Resumed notification {} from quiet-hours deferral.", notification.getId());
        }
    }
}
