package io.github.andres.pigeon.application;

import io.github.andres.pigeon.application.port.out.AuditLogPort;
import io.github.andres.pigeon.application.port.out.ClockPort;
import io.github.andres.pigeon.application.port.out.ContactRepository;
import io.github.andres.pigeon.application.port.out.CustomerPreferenceRepository;
import io.github.andres.pigeon.application.port.out.EmailSenderPort;
import io.github.andres.pigeon.application.port.out.NotificationRepository;
import io.github.andres.pigeon.application.port.out.PushSenderPort;
import io.github.andres.pigeon.application.port.out.SmsSenderPort;
import io.github.andres.pigeon.application.port.out.TemplateEnginePort;
import io.github.andres.pigeon.application.service.DeliveryOrchestratorService;
import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.domain.enums.FailureReason;
import io.github.andres.pigeon.domain.enums.NotificationStatus;
import io.github.andres.pigeon.domain.model.CustomerContact;
import io.github.andres.pigeon.domain.model.CustomerPreference;
import io.github.andres.pigeon.domain.model.Notification;
import io.github.andres.pigeon.domain.vo.CustomerId;
import io.github.andres.pigeon.domain.vo.IdempotencyKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryOrchestratorServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private ContactRepository contactRepository;

    @Mock
    private CustomerPreferenceRepository preferenceRepository;

    @Mock
    private PushSenderPort pushSenderPort;

    @Mock
    private SmsSenderPort smsSenderPort;

    @Mock
    private EmailSenderPort emailSenderPort;

    @Mock
    private TemplateEnginePort templateEnginePort;

    @Mock
    private AuditLogPort auditLogPort;

    @Mock
    private ClockPort clockPort;

    private DeliveryOrchestratorService orchestrator;
    private Instant now;
    private Notification notification;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-10-01T12:00:00Z");
        org.mockito.Mockito.lenient().when(clockPort.now()).thenReturn(now);
        org.mockito.Mockito.lenient().when(templateEnginePort.render(any(), any(), any(), any()))
                .thenReturn(new TemplateEnginePort.RenderedMessage("Subject", "Body content", "v1.0.0"));

        orchestrator = new DeliveryOrchestratorService(
                notificationRepository,
                contactRepository,
                preferenceRepository,
                pushSenderPort,
                smsSenderPort,
                emailSenderPort,
                templateEnginePort,
                auditLogPort,
                clockPort
        );

        notification = Notification.createPending(
                "bank-client",
                IdempotencyKey.of("key-1"),
                "hash-1",
                CustomerId.of("cus_8F2A91"),
                EventType.TRANSFER_COMPLETED,
                "es",
                Map.of(),
                now
        );
    }

    @Test
    @DisplayName("Should deliver PUSH successfully as primary preferred channel without invoking SMS or EMAIL")
    void shouldDeliverPushSuccessfully() {
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(contactRepository.findByCustomerId(notification.getCustomerId()))
                .thenReturn(Optional.of(CustomerContact.of("cus_8F2A91", "maria@example.com", "+15550198234", "push_tok_123")));
        when(preferenceRepository.findByCustomerId(notification.getCustomerId()))
                .thenReturn(Optional.of(CustomerPreference.defaultPreference(notification.getCustomerId(), now)));
        when(pushSenderPort.sendPush(eq("push_tok_123"), eq(notification), any(), any()))
                .thenReturn(PushSenderPort.PushSendResult.success("wm_push_111", 45L));

        orchestrator.process(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(notification.getAttempts()).hasSize(1);
        assertThat(notification.getAttempts().get(0).channel()).isEqualTo(Channel.PUSH);
        assertThat(notification.getAttempts().get(0).outcome()).isEqualTo("SUCCESS");

        verify(smsSenderPort, never()).sendSms(any(), any());
        verify(emailSenderPort, never()).sendEmail(any(), any());
        verify(notificationRepository).save(notification);
    }

    @Test
    @DisplayName("Should execute full fallback cascade PUSH -> SMS -> EMAIL when PUSH and SMS fail")
    void shouldExecuteFullFallbackCascade() {
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(contactRepository.findByCustomerId(notification.getCustomerId()))
                .thenReturn(Optional.of(CustomerContact.of("cus_8F2A91", "maria@example.com", "+15550198234", "push_tok_123")));
        when(preferenceRepository.findByCustomerId(notification.getCustomerId()))
                .thenReturn(Optional.of(CustomerPreference.defaultPreference(notification.getCustomerId(), now)));

        when(pushSenderPort.sendPush(eq("push_tok_123"), eq(notification), any(), any()))
                .thenReturn(PushSenderPort.PushSendResult.failure("CIRCUIT_OPEN", 10L));
        when(smsSenderPort.sendSms(eq("+15550198234"), eq(notification), any()))
                .thenReturn(SmsSenderPort.SmsSendResult.failure("PROVIDER_UNAVAILABLE", 200L));
        when(emailSenderPort.sendEmail(eq("maria@example.com"), eq(notification)))
                .thenReturn(EmailSenderPort.EmailSendResult.ok("mailhog-123", 80L));

        orchestrator.process(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(notification.getAttempts()).hasSize(3);
        assertThat(notification.getAttempts().get(0).channel()).isEqualTo(Channel.PUSH);
        assertThat(notification.getAttempts().get(0).outcome()).isEqualTo("FAILURE");
        assertThat(notification.getAttempts().get(1).channel()).isEqualTo(Channel.SMS);
        assertThat(notification.getAttempts().get(1).outcome()).isEqualTo("FAILURE");
        assertThat(notification.getAttempts().get(2).channel()).isEqualTo(Channel.EMAIL);
        assertThat(notification.getAttempts().get(2).outcome()).isEqualTo("SUCCESS");

        verify(notificationRepository).save(notification);
    }

    @Test
    @DisplayName("Should mark FAILED with ALL_CHANNELS_EXHAUSTED when all candidate channels fail")
    void shouldFailWhenAllChannelsFail() {
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(contactRepository.findByCustomerId(notification.getCustomerId()))
                .thenReturn(Optional.of(CustomerContact.of("cus_8F2A91", "maria@example.com", "+15550198234", "push_tok_123")));
        when(preferenceRepository.findByCustomerId(notification.getCustomerId()))
                .thenReturn(Optional.of(CustomerPreference.defaultPreference(notification.getCustomerId(), now)));

        when(pushSenderPort.sendPush(any(), any(), any(), any()))
                .thenReturn(PushSenderPort.PushSendResult.failure("CIRCUIT_OPEN", 10L));
        when(smsSenderPort.sendSms(any(), any(), any()))
                .thenReturn(SmsSenderPort.SmsSendResult.failure("PROVIDER_UNAVAILABLE", 500L));
        when(emailSenderPort.sendEmail(any(), any()))
                .thenReturn(EmailSenderPort.EmailSendResult.error("SMTP_TIMEOUT", 2000L));

        orchestrator.process(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getFailureReason()).isEqualTo(FailureReason.ALL_CHANNELS_EXHAUSTED);
        assertThat(notification.getAttempts()).hasSize(3);

        verify(notificationRepository).save(notification);
    }

    @Test
    @DisplayName("Should fail with SUPPRESSED_NO_ALLOWED_CHANNEL when customer preference blocks all channels")
    void shouldFailWhenNoChannelsAllowed() {
        CustomerPreference restrictivePref = new CustomerPreference(
                notification.getCustomerId(),
                List.of(), // no channels allowed
                List.of(),
                Set.of(),
                ZoneId.of("UTC"),
                true,
                now,
                now
        );
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(contactRepository.findByCustomerId(notification.getCustomerId()))
                .thenReturn(Optional.of(CustomerContact.of("cus_8F2A91", "maria@example.com", "+15550198234", "push_tok_123")));
        when(preferenceRepository.findByCustomerId(notification.getCustomerId()))
                .thenReturn(Optional.of(restrictivePref));

        orchestrator.process(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getFailureReason()).isEqualTo(FailureReason.SUPPRESSED_NO_ALLOWED_CHANNEL);

        verify(pushSenderPort, never()).sendPush(any(), any(), any(), any());
        verify(smsSenderPort, never()).sendSms(any(), any());
        verify(emailSenderPort, never()).sendEmail(any(), any());
    }

    @Test
    @DisplayName("Mandatory security message ignores channel suppression rules")
    void mandatorySecurityMessageIgnoresSuppression() {
        Notification otpNotification = Notification.createPending(
                "bank-client",
                IdempotencyKey.of("key-otp"),
                "hash-otp",
                CustomerId.of("cus_8F2A91"),
                EventType.OTP_REQUESTED,
                "es",
                Map.of(),
                now
        );
        CustomerPreference restrictivePref = new CustomerPreference(
                otpNotification.getCustomerId(),
                List.of(), // no channels allowed for marketing
                List.of(Channel.SMS),
                Set.of(),
                ZoneId.of("UTC"),
                true,
                now,
                now
        );
        when(notificationRepository.findById(otpNotification.getId())).thenReturn(Optional.of(otpNotification));
        when(contactRepository.findByCustomerId(otpNotification.getCustomerId()))
                .thenReturn(Optional.of(CustomerContact.of("cus_8F2A91", null, "+15550198234", null)));
        when(preferenceRepository.findByCustomerId(otpNotification.getCustomerId()))
                .thenReturn(Optional.of(restrictivePref));
        when(smsSenderPort.sendSms(eq("+15550198234"), eq(otpNotification), any()))
                .thenReturn(SmsSenderPort.SmsSendResult.success("wm_sms_otp", 20L));

        orchestrator.process(otpNotification.getId());

        assertThat(otpNotification.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
        verify(smsSenderPort).sendSms(any(), any(), any());
    }

    @Test
    @DisplayName("Should skip processing if notification is DEFERRED due to quiet hours")
    void shouldSkipProcessingIfDeferred() {
        Notification deferred = Notification.createDeferred(
                "bank-client",
                IdempotencyKey.of("key-def"),
                "hash-def",
                CustomerId.of("cus_8F2A91"),
                EventType.TRANSFER_COMPLETED,
                "es",
                Map.of(),
                now.plusSeconds(3600),
                now
        );
        when(notificationRepository.findById(deferred.getId())).thenReturn(Optional.of(deferred));

        orchestrator.process(deferred.getId());

        verify(contactRepository, never()).findByCustomerId(any());
        verify(pushSenderPort, never()).sendPush(any(), any(), any(), any());
        verify(smsSenderPort, never()).sendSms(any(), any());
        verify(emailSenderPort, never()).sendEmail(any(), any());
    }

    @Test
    @DisplayName("Should skip processing if notification is already terminal")
    void shouldSkipProcessingIfTerminal() {
        notification.markFailed(FailureReason.EXPIRED, Channel.SMS, "system", null, "expired", now);
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));

        orchestrator.process(notification.getId());

        verify(contactRepository, never()).findByCustomerId(any());
        verify(pushSenderPort, never()).sendPush(any(), any(), any(), any());
        verify(smsSenderPort, never()).sendSms(any(), any());
        verify(emailSenderPort, never()).sendEmail(any(), any());
    }
}
