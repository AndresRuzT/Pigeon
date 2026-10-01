package io.github.andres.pigeon.application.service;

import io.github.andres.pigeon.application.port.in.ProcessNotificationUseCase;
import io.github.andres.pigeon.application.port.out.AuditLogPort;
import io.github.andres.pigeon.application.port.out.ClockPort;
import io.github.andres.pigeon.application.port.out.ContactRepository;
import io.github.andres.pigeon.application.port.out.CustomerPreferenceRepository;
import io.github.andres.pigeon.application.port.out.EmailSenderPort;
import io.github.andres.pigeon.application.port.out.NotificationRepository;
import io.github.andres.pigeon.application.port.out.PushSenderPort;
import io.github.andres.pigeon.application.port.out.SmsSenderPort;
import io.github.andres.pigeon.application.port.out.TemplateEnginePort;
import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.enums.FailureReason;
import io.github.andres.pigeon.domain.enums.NotificationStatus;
import io.github.andres.pigeon.domain.exception.NotificationNotFoundException;
import io.github.andres.pigeon.domain.model.AuditRecord;
import io.github.andres.pigeon.domain.model.CustomerContact;
import io.github.andres.pigeon.domain.model.CustomerPreference;
import io.github.andres.pigeon.domain.model.Notification;
import io.github.andres.pigeon.domain.policy.MandatoryMessagePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class DeliveryOrchestratorService implements ProcessNotificationUseCase {

    private static final Logger log = LoggerFactory.getLogger(DeliveryOrchestratorService.class);

    private final NotificationRepository notificationRepository;
    private final ContactRepository contactRepository;
    private final CustomerPreferenceRepository preferenceRepository;
    private final PushSenderPort pushSenderPort;
    private final SmsSenderPort smsSenderPort;
    private final EmailSenderPort emailSenderPort;
    private final TemplateEnginePort templateEnginePort;
    private final AuditLogPort auditLogPort;
    private final ClockPort clockPort;

    public DeliveryOrchestratorService(
            NotificationRepository notificationRepository,
            ContactRepository contactRepository,
            CustomerPreferenceRepository preferenceRepository,
            PushSenderPort pushSenderPort,
            SmsSenderPort smsSenderPort,
            EmailSenderPort emailSenderPort,
            TemplateEnginePort templateEnginePort,
            AuditLogPort auditLogPort,
            ClockPort clockPort
    ) {
        this.notificationRepository = notificationRepository;
        this.contactRepository = contactRepository;
        this.preferenceRepository = preferenceRepository;
        this.pushSenderPort = pushSenderPort;
        this.smsSenderPort = smsSenderPort;
        this.emailSenderPort = emailSenderPort;
        this.templateEnginePort = templateEnginePort;
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

        if (notification.getStatus() == NotificationStatus.DEFERRED) {
            log.info("Notification {} is DEFERRED due to quiet hours, skipping immediate processing.", notificationId);
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
        boolean isMandatory = MandatoryMessagePolicy.isMandatory(notification.getEventType());

        CustomerPreference preference = preferenceRepository.findByCustomerId(notification.getCustomerId())
                .orElse(CustomerPreference.defaultPreference(notification.getCustomerId(), now));

        List<Channel> candidateChannels = preference.getPreferredChannelOrder();
        if (!isMandatory) {
            candidateChannels = candidateChannels.stream()
                    .filter(preference::isChannelAllowed)
                    .toList();
        }

        if (candidateChannels.isEmpty()) {
            log.warn("No allowed channels configured for customer {} on non-mandatory event", notification.getCustomerId().value());
            AuditRecord failedAudit = notification.markFailed(
                    FailureReason.SUPPRESSED_NO_ALLOWED_CHANNEL,
                    null,
                    "system",
                    null,
                    "All candidate channels suppressed by customer preference",
                    now
            );
            notificationRepository.save(notification);
            auditLogPort.append(failedAudit);
            return;
        }

        boolean anyAttempted = false;
        Channel lastAttemptedChannel = null;

        for (Channel channel : candidateChannels) {
            switch (channel) {
                case PUSH -> {
                    if (contact.pushToken() != null && !contact.pushToken().isBlank()) {
                        anyAttempted = true;
                        lastAttemptedChannel = Channel.PUSH;
                        TemplateEnginePort.RenderedMessage rendered = templateEnginePort.render(
                                notification.getEventType(), Channel.PUSH, notification.getLocale(), notification.getData()
                        );
                        notification.setTemplateDetails("push_" + notification.getEventType().name().toLowerCase(), rendered.templateVersion());

                        PushSenderPort.PushSendResult pushResult = pushSenderPort.sendPush(
                                contact.pushToken(), notification, rendered.subjectOrTitle(), rendered.body()
                        );

                        if (pushResult != null && pushResult.success()) {
                            AuditRecord sentAudit = notification.markSent(
                                    Channel.PUSH, pushResult.providerRef(), pushResult.latencyMs(), "system", null, now
                            );
                            auditLogPort.append(sentAudit);

                            AuditRecord deliveredAudit = notification.markDelivered(Channel.PUSH, "system", null, now);
                            auditLogPort.append(deliveredAudit);

                            notificationRepository.save(notification);
                            log.info("Notification {} successfully delivered via PUSH to customer {}", notificationId, notification.getCustomerId().value());
                            return;
                        } else {
                            notification.recordFailedAttempt(Channel.PUSH, pushResult.errorCode(), pushResult.latencyMs(), now);
                            log.warn("PUSH delivery failed for notification {} with {}. Continuing fallback chain.",
                                    notificationId, pushResult.errorCode());
                        }
                    }
                }
                case SMS -> {
                    if (contact.phone() != null && !contact.phone().isBlank()) {
                        anyAttempted = true;
                        lastAttemptedChannel = Channel.SMS;
                        TemplateEnginePort.RenderedMessage rendered = templateEnginePort.render(
                                notification.getEventType(), Channel.SMS, notification.getLocale(), notification.getData()
                        );
                        notification.setTemplateDetails("sms_" + notification.getEventType().name().toLowerCase(), rendered.templateVersion());

                        SmsSenderPort.SmsSendResult smsResult = smsSenderPort.sendSms(contact.phone(), notification, rendered.body());
                        if (smsResult.success()) {
                            AuditRecord sentAudit = notification.markSent(
                                    Channel.SMS, smsResult.providerRef(), smsResult.latencyMs(), "system", null, now
                            );
                            auditLogPort.append(sentAudit);

                            AuditRecord deliveredAudit = notification.markDelivered(Channel.SMS, "system", null, now);
                            auditLogPort.append(deliveredAudit);

                            notificationRepository.save(notification);
                            log.info("Notification {} successfully delivered via SMS to {}", notificationId, contact.phone());
                            return;
                        } else {
                            notification.recordFailedAttempt(Channel.SMS, smsResult.errorCode(), smsResult.latencyMs(), now);
                            log.warn("SMS delivery failed for notification {} with {}. Continuing fallback chain.",
                                    notificationId, smsResult.errorCode());
                        }
                    }
                }
                case EMAIL -> {
                    if (contact.email() != null && !contact.email().isBlank()) {
                        anyAttempted = true;
                        lastAttemptedChannel = Channel.EMAIL;
                        TemplateEnginePort.RenderedMessage rendered = templateEnginePort.render(
                                notification.getEventType(), Channel.EMAIL, notification.getLocale(), notification.getData()
                        );
                        notification.setTemplateDetails("email_" + notification.getEventType().name().toLowerCase(), rendered.templateVersion());

                        EmailSenderPort.EmailSendResult emailResult = emailSenderPort.sendEmail(contact.email(), notification);
                        if (emailResult.success()) {
                            AuditRecord sentAudit = notification.markSent(
                                    Channel.EMAIL, emailResult.providerRef(), emailResult.latencyMs(), "system", null, now
                            );
                            auditLogPort.append(sentAudit);

                            AuditRecord deliveredAudit = notification.markDelivered(Channel.EMAIL, "system", null, now);
                            auditLogPort.append(deliveredAudit);

                            notificationRepository.save(notification);
                            log.info("Notification {} successfully delivered via EMAIL fallback to {}", notificationId, contact.email());
                            return;
                        } else {
                            notification.recordFailedAttempt(Channel.EMAIL, emailResult.errorCode(), emailResult.latencyMs(), now);
                            log.error("Email fallback failed for notification {} with {}.", notificationId, emailResult.errorCode());
                        }
                    }
                }
            }
        }

        if (!anyAttempted) {
            log.warn("No valid destinations found across candidate channels for customer {}", notification.getCustomerId().value());
            AuditRecord failedAudit = notification.markFailed(
                    FailureReason.INVALID_DESTINATION,
                    null,
                    "system",
                    null,
                    "No valid destination addresses found for candidate channels",
                    now
            );
            notificationRepository.save(notification);
            auditLogPort.append(failedAudit);
            return;
        }

        // All attempted channels exhausted
        AuditRecord failedAudit = notification.markFailed(
                FailureReason.ALL_CHANNELS_EXHAUSTED,
                lastAttemptedChannel,
                "system",
                null,
                "All candidate channels failed delivery",
                now
        );
        notificationRepository.save(notification);
        auditLogPort.append(failedAudit);
        log.error("Notification {} permanently failed: all channels exhausted", notificationId);
    }
}
