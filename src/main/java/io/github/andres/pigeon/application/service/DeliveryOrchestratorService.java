package io.github.andres.pigeon.application.service;

import io.github.andres.pigeon.application.port.in.ProcessNotificationUseCase;
import io.github.andres.pigeon.application.port.out.AuditLogPort;
import io.github.andres.pigeon.application.port.out.ClockPort;
import io.github.andres.pigeon.application.port.out.ContactRepository;
import io.github.andres.pigeon.application.port.out.EmailSenderPort;
import io.github.andres.pigeon.application.port.out.NotificationRepository;
import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.enums.FailureReason;
import io.github.andres.pigeon.domain.exception.NotificationNotFoundException;
import io.github.andres.pigeon.domain.model.AuditRecord;
import io.github.andres.pigeon.domain.model.CustomerContact;
import io.github.andres.pigeon.domain.model.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class DeliveryOrchestratorService implements ProcessNotificationUseCase {

    private static final Logger log = LoggerFactory.getLogger(DeliveryOrchestratorService.class);

    private final NotificationRepository notificationRepository;
    private final ContactRepository contactRepository;
    private final EmailSenderPort emailSenderPort;
    private final AuditLogPort auditLogPort;
    private final ClockPort clockPort;

    public DeliveryOrchestratorService(
            NotificationRepository notificationRepository,
            ContactRepository contactRepository,
            EmailSenderPort emailSenderPort,
            AuditLogPort auditLogPort,
            ClockPort clockPort
    ) {
        this.notificationRepository = notificationRepository;
        this.contactRepository = contactRepository;
        this.emailSenderPort = emailSenderPort;
        this.auditLogPort = auditLogPort;
        this.clockPort = clockPort;
    }

    @Override
    @Transactional
    public void process(UUID notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new NotificationNotFoundException(notificationId));

        if (notification.isTerminal()) {
            log.info("Notification {} is already in terminal state {}, skipping processing.", notificationId, notification.getStatus());
            return;
        }

        Instant now = clockPort.now();
        Optional<CustomerContact> contactOpt = contactRepository.findByCustomerId(notification.getCustomerId());

        if (contactOpt.isEmpty() || contactOpt.get().email() == null || contactOpt.get().email().isBlank()) {
            log.warn("No email destination found for customer {}", notification.getCustomerId().value());
            AuditRecord failedAudit = notification.markFailed(
                    FailureReason.INVALID_DESTINATION,
                    Channel.EMAIL,
                    "system",
                    null,
                    "No valid email address registered for customer",
                    now
            );
            notificationRepository.save(notification);
            auditLogPort.append(failedAudit);
            return;
        }

        String recipientEmail = contactOpt.get().email();
        EmailSenderPort.EmailSendResult sendResult = emailSenderPort.sendEmail(recipientEmail, notification);

        if (sendResult.success()) {
            // Invariant 2: DELIVERED can only be reached from SENT
            // Invariant 4: Every transition writes exactly one audit record
            AuditRecord sentAudit = notification.markSent(
                    Channel.EMAIL,
                    sendResult.providerRef(),
                    sendResult.latencyMs(),
                    "system",
                    null,
                    now
            );
            auditLogPort.append(sentAudit);

            // Policy ON_ACCEPT: mark DELIVERED right after SENT for simulated email
            AuditRecord deliveredAudit = notification.markDelivered(
                    Channel.EMAIL,
                    "system",
                    null,
                    now
            );
            auditLogPort.append(deliveredAudit);

            notificationRepository.save(notification);
            log.info("Notification {} successfully sent and delivered to {}", notificationId, recipientEmail);
        } else {
            notification.recordFailedAttempt(Channel.EMAIL, sendResult.errorCode(), sendResult.latencyMs(), now);
            AuditRecord failedAudit = notification.markFailed(
                    FailureReason.PROVIDER_UNAVAILABLE,
                    Channel.EMAIL,
                    "system",
                    null,
                    "Email send failed: " + sendResult.errorCode(),
                    now
            );
            notificationRepository.save(notification);
            auditLogPort.append(failedAudit);
            log.error("Notification {} failed delivery via EMAIL: {}", notificationId, sendResult.errorCode());
        }
    }
}
