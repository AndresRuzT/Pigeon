package io.github.andres.pigeon.application.service;

import io.github.andres.pigeon.application.port.in.ProcessNotificationUseCase;
import io.github.andres.pigeon.application.port.out.AuditLogPort;
import io.github.andres.pigeon.application.port.out.ClockPort;
import io.github.andres.pigeon.application.port.out.ContactRepository;
import io.github.andres.pigeon.application.port.out.EmailSenderPort;
import io.github.andres.pigeon.application.port.out.NotificationRepository;
import io.github.andres.pigeon.application.port.out.SmsSenderPort;
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
    private final SmsSenderPort smsSenderPort;
    private final AuditLogPort auditLogPort;
    private final ClockPort clockPort;

    public DeliveryOrchestratorService(
            NotificationRepository notificationRepository,
            ContactRepository contactRepository,
            EmailSenderPort emailSenderPort,
            SmsSenderPort smsSenderPort,
            AuditLogPort auditLogPort,
            ClockPort clockPort
    ) {
        this.notificationRepository = notificationRepository;
        this.contactRepository = contactRepository;
        this.emailSenderPort = emailSenderPort;
        this.smsSenderPort = smsSenderPort;
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

        if (contactOpt.isEmpty()) {
            log.warn("No contact profile found for customer {}", notification.getCustomerId().value());
            AuditRecord failedAudit = notification.markFailed(
                    FailureReason.INVALID_DESTINATION,
                    Channel.SMS,
                    "system",
                    null,
                    "No contact details registered for customer",
                    now
            );
            notificationRepository.save(notification);
            auditLogPort.append(failedAudit);
            return;
        }

        CustomerContact contact = contactOpt.get();
        boolean hasPhone = contact.phone() != null && !contact.phone().isBlank();
        boolean hasEmail = contact.email() != null && !contact.email().isBlank();

        if (!hasPhone && !hasEmail) {
            log.warn("No valid destinations (phone or email) found for customer {}", notification.getCustomerId().value());
            AuditRecord failedAudit = notification.markFailed(
                    FailureReason.INVALID_DESTINATION,
                    Channel.SMS,
                    "system",
                    null,
                    "No valid phone or email registered for customer",
                    now
            );
            notificationRepository.save(notification);
            auditLogPort.append(failedAudit);
            return;
        }

        // 1. Primary Channel: Attempt SMS if phone destination is present
        if (hasPhone) {
            SmsSenderPort.SmsSendResult smsResult = smsSenderPort.sendSms(contact.phone(), notification);
            if (smsResult.success()) {
                AuditRecord sentAudit = notification.markSent(
                        Channel.SMS,
                        smsResult.providerRef(),
                        smsResult.latencyMs(),
                        "system",
                        null,
                        now
                );
                auditLogPort.append(sentAudit);

                AuditRecord deliveredAudit = notification.markDelivered(
                        Channel.SMS,
                        "system",
                        null,
                        now
                );
                auditLogPort.append(deliveredAudit);

                notificationRepository.save(notification);
                log.info("Notification {} successfully delivered via SMS to {}", notificationId, contact.phone());
                return;
            } else {
                notification.recordFailedAttempt(Channel.SMS, smsResult.errorCode(), smsResult.latencyMs(), now);
                log.warn("SMS delivery failed for notification {} with {}. Triggering fallback to EMAIL.",
                        notificationId, smsResult.errorCode());
            }
        }

        // 2. Fallback Channel: Attempt EMAIL if email destination is present
        if (hasEmail) {
            EmailSenderPort.EmailSendResult emailResult = emailSenderPort.sendEmail(contact.email(), notification);
            if (emailResult.success()) {
                AuditRecord sentAudit = notification.markSent(
                        Channel.EMAIL,
                        emailResult.providerRef(),
                        emailResult.latencyMs(),
                        "system",
                        null,
                        now
                );
                auditLogPort.append(sentAudit);

                AuditRecord deliveredAudit = notification.markDelivered(
                        Channel.EMAIL,
                        "system",
                        null,
                        now
                );
                auditLogPort.append(deliveredAudit);

                notificationRepository.save(notification);
                log.info("Notification {} successfully delivered via EMAIL fallback to {}", notificationId, contact.email());
                return;
            } else {
                notification.recordFailedAttempt(Channel.EMAIL, emailResult.errorCode(), emailResult.latencyMs(), now);
                log.error("Email fallback failed for notification {} with {}.", notificationId, emailResult.errorCode());
            }
        }

        // 3. Terminal Failure: All candidate channels exhausted
        AuditRecord failedAudit = notification.markFailed(
                FailureReason.ALL_CHANNELS_EXHAUSTED,
                hasEmail ? Channel.EMAIL : Channel.SMS,
                "system",
                null,
                "All attempted channels failed delivery",
                now
        );
        notificationRepository.save(notification);
        auditLogPort.append(failedAudit);
        log.error("Notification {} permanently failed: all channels exhausted", notificationId);
    }
}
