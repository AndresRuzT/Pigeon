package io.github.andres.pigeon.application.service;

import io.github.andres.pigeon.application.port.in.ProcessWebhookReceiptUseCase;
import io.github.andres.pigeon.application.port.out.AuditLogPort;
import io.github.andres.pigeon.application.port.out.ClockPort;
import io.github.andres.pigeon.application.port.out.MetricsPort;
import io.github.andres.pigeon.application.port.out.NotificationRepository;
import io.github.andres.pigeon.domain.enums.FailureReason;
import io.github.andres.pigeon.domain.enums.NotificationStatus;
import io.github.andres.pigeon.domain.exception.InvalidStateTransitionException;
import io.github.andres.pigeon.domain.exception.NotificationNotFoundException;
import io.github.andres.pigeon.domain.model.AuditRecord;
import io.github.andres.pigeon.domain.model.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

@Service
public class ProcessWebhookReceiptService implements ProcessWebhookReceiptUseCase {

    private static final Logger log = LoggerFactory.getLogger(ProcessWebhookReceiptService.class);

    private final NotificationRepository notificationRepository;
    private final AuditLogPort auditLogPort;
    private final MetricsPort metricsPort;
    private final ClockPort clockPort;

    public ProcessWebhookReceiptService(
            NotificationRepository notificationRepository,
            AuditLogPort auditLogPort,
            MetricsPort metricsPort,
            ClockPort clockPort
    ) {
        this.notificationRepository = notificationRepository;
        this.auditLogPort = auditLogPort;
        this.metricsPort = metricsPort;
        this.clockPort = clockPort;
    }

    @Override
    @Transactional
    public ReceiptResult processReceipt(ReceiptCommand command) {
        Objects.requireNonNull(command.notificationId(), "notificationId cannot be null in webhook receipt");

        Notification notification = notificationRepository.findById(command.notificationId())
                .orElseThrow(() -> new NotificationNotFoundException(command.notificationId()));

        if (notification.isTerminal()) {
            log.info("Notification {} is already in terminal state {}, ignoring duplicate receipt",
                    notification.getId(), notification.getStatus());
            return new ReceiptResult(notification.getId(), notification.getStatus(), false);
        }

        if (notification.getStatus() != NotificationStatus.SENT) {
            log.warn("Cannot process delivery receipt for notification {} with status {}",
                    notification.getId(), notification.getStatus());
            throw new InvalidStateTransitionException(notification.getStatus(), command.status());
        }

        Instant occurredAt = command.occurredAt() != null ? command.occurredAt() : clockPort.now();

        if (command.status() == NotificationStatus.DELIVERED) {
            AuditRecord audit = notification.markDelivered(command.channel(), "webhook", command.reason(), occurredAt);
            notificationRepository.save(notification);
            auditLogPort.append(audit);
            metricsPort.recordNotificationFinal(NotificationStatus.DELIVERED, null);
            log.info("Notification {} marked DELIVERED via {} receipt", notification.getId(), command.channel());
            return new ReceiptResult(notification.getId(), NotificationStatus.DELIVERED, true);
        } else if (command.status() == NotificationStatus.FAILED) {
            FailureReason reason = FailureReason.PROVIDER_REJECTED;
            AuditRecord audit = notification.markFailed(reason, command.channel(), "webhook", null, command.reason(), occurredAt);
            notificationRepository.save(notification);
            auditLogPort.append(audit);
            metricsPort.recordNotificationFinal(NotificationStatus.FAILED, reason.name());
            log.warn("Notification {} marked FAILED via {} receipt, reason: {}", notification.getId(), command.channel(), command.reason());
            return new ReceiptResult(notification.getId(), NotificationStatus.FAILED, true);
        } else {
            throw new IllegalArgumentException("Invalid receipt status: " + command.status());
        }
    }
}
