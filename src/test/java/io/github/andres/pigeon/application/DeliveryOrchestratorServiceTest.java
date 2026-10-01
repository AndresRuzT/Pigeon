package io.github.andres.pigeon.application;

import io.github.andres.pigeon.application.port.out.AuditLogPort;
import io.github.andres.pigeon.application.port.out.ClockPort;
import io.github.andres.pigeon.application.port.out.ContactRepository;
import io.github.andres.pigeon.application.port.out.EmailSenderPort;
import io.github.andres.pigeon.application.port.out.NotificationRepository;
import io.github.andres.pigeon.application.port.out.SmsSenderPort;
import io.github.andres.pigeon.application.service.DeliveryOrchestratorService;
import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.domain.enums.FailureReason;
import io.github.andres.pigeon.domain.enums.NotificationStatus;
import io.github.andres.pigeon.domain.model.CustomerContact;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeliveryOrchestratorServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private ContactRepository contactRepository;

    @Mock
    private EmailSenderPort emailSenderPort;

    @Mock
    private SmsSenderPort smsSenderPort;

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
        orchestrator = new DeliveryOrchestratorService(
                notificationRepository,
                contactRepository,
                emailSenderPort,
                smsSenderPort,
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
    @DisplayName("Should deliver SMS successfully as primary channel without calling email")
    void shouldDeliverSmsSuccessfully() {
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(contactRepository.findByCustomerId(notification.getCustomerId()))
                .thenReturn(Optional.of(CustomerContact.of("cus_8F2A91", "maria@example.com", "+15550198234", null)));
        when(smsSenderPort.sendSms(eq("+15550198234"), eq(notification)))
                .thenReturn(SmsSenderPort.SmsSendResult.success("wm_sms_999", 50L));

        orchestrator.process(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(notification.getAttempts()).hasSize(1);
        assertThat(notification.getAttempts().get(0).channel()).isEqualTo(Channel.SMS);
        assertThat(notification.getAttempts().get(0).outcome()).isEqualTo("SUCCESS");

        verify(emailSenderPort, never()).sendEmail(any(), any());
        verify(notificationRepository).save(notification);
        verify(auditLogPort, times(2)).append(any());
    }

    @Test
    @DisplayName("Should fallback to EMAIL when SMS fails")
    void shouldFallbackToEmailWhenSmsFails() {
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(contactRepository.findByCustomerId(notification.getCustomerId()))
                .thenReturn(Optional.of(CustomerContact.of("cus_8F2A91", "maria@example.com", "+15550198234", null)));
        when(smsSenderPort.sendSms(eq("+15550198234"), eq(notification)))
                .thenReturn(SmsSenderPort.SmsSendResult.failure("CIRCUIT_OPEN", 10L));
        when(emailSenderPort.sendEmail(eq("maria@example.com"), eq(notification)))
                .thenReturn(EmailSenderPort.EmailSendResult.ok("mailhog-123", 100L));

        orchestrator.process(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(notification.getAttempts()).hasSize(2);
        assertThat(notification.getAttempts().get(0).channel()).isEqualTo(Channel.SMS);
        assertThat(notification.getAttempts().get(0).outcome()).isEqualTo("FAILURE");
        assertThat(notification.getAttempts().get(1).channel()).isEqualTo(Channel.EMAIL);
        assertThat(notification.getAttempts().get(1).outcome()).isEqualTo("SUCCESS");

        verify(notificationRepository).save(notification);
        verify(auditLogPort, times(2)).append(any());
    }

    @Test
    @DisplayName("Should mark FAILED with ALL_CHANNELS_EXHAUSTED when both SMS and EMAIL fail")
    void shouldFailWhenAllChannelsFail() {
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(contactRepository.findByCustomerId(notification.getCustomerId()))
                .thenReturn(Optional.of(CustomerContact.of("cus_8F2A91", "maria@example.com", "+15550198234", null)));
        when(smsSenderPort.sendSms(eq("+15550198234"), eq(notification)))
                .thenReturn(SmsSenderPort.SmsSendResult.failure("PROVIDER_UNAVAILABLE", 500L));
        when(emailSenderPort.sendEmail(eq("maria@example.com"), eq(notification)))
                .thenReturn(EmailSenderPort.EmailSendResult.error("SMTP_TIMEOUT", 2000L));

        orchestrator.process(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getFailureReason()).isEqualTo(FailureReason.ALL_CHANNELS_EXHAUSTED);
        assertThat(notification.getAttempts()).hasSize(2);

        verify(notificationRepository).save(notification);
        verify(auditLogPort).append(any());
    }

    @Test
    @DisplayName("Should deliver directly to email when phone destination is absent")
    void shouldDeliverDirectlyToEmailWhenNoPhone() {
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(contactRepository.findByCustomerId(notification.getCustomerId()))
                .thenReturn(Optional.of(CustomerContact.of("cus_8F2A91", "maria@example.com", null, null)));
        when(emailSenderPort.sendEmail(eq("maria@example.com"), eq(notification)))
                .thenReturn(EmailSenderPort.EmailSendResult.ok("mailhog-123", 90L));

        orchestrator.process(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(notification.getAttempts()).hasSize(1);
        assertThat(notification.getAttempts().get(0).channel()).isEqualTo(Channel.EMAIL);

        verify(smsSenderPort, never()).sendSms(any(), any());
    }

    @Test
    @DisplayName("Should fail delivery if customer contact has no destinations")
    void shouldFailWhenNoDestinations() {
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(contactRepository.findByCustomerId(notification.getCustomerId()))
                .thenReturn(Optional.of(CustomerContact.of("cus_8F2A91", null, null, null)));

        orchestrator.process(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getFailureReason()).isEqualTo(FailureReason.INVALID_DESTINATION);

        verify(smsSenderPort, never()).sendSms(any(), any());
        verify(emailSenderPort, never()).sendEmail(any(), any());
        verify(notificationRepository).save(notification);
        verify(auditLogPort).append(any());
    }

    @Test
    @DisplayName("Should skip processing if notification is already terminal")
    void shouldSkipProcessingIfTerminal() {
        notification.markFailed(FailureReason.EXPIRED, Channel.SMS, "system", null, "expired", now);
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));

        orchestrator.process(notification.getId());

        verify(contactRepository, never()).findByCustomerId(any());
        verify(smsSenderPort, never()).sendSms(any(), any());
        verify(emailSenderPort, never()).sendEmail(any(), any());
    }
}
