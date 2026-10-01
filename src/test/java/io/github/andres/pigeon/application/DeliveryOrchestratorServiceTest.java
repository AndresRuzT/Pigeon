package io.github.andres.pigeon.application;

import io.github.andres.pigeon.application.port.out.AuditLogPort;
import io.github.andres.pigeon.application.port.out.ClockPort;
import io.github.andres.pigeon.application.port.out.ContactRepository;
import io.github.andres.pigeon.application.port.out.EmailSenderPort;
import io.github.andres.pigeon.application.port.out.NotificationRepository;
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
import java.util.UUID;

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
    private AuditLogPort auditLogPort;

    @Mock
    private ClockPort clockPort;

    private DeliveryOrchestratorService orchestrator;
    private Instant now;
    private Notification notification;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-10-01T12:00:00Z");
        when(clockPort.now()).thenReturn(now);
        orchestrator = new DeliveryOrchestratorService(
                notificationRepository,
                contactRepository,
                emailSenderPort,
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
    @DisplayName("Should successfully deliver email and transition through SENT to DELIVERED (ON_ACCEPT policy)")
    void shouldDeliverEmailSuccessfully() {
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(contactRepository.findByCustomerId(notification.getCustomerId()))
                .thenReturn(Optional.of(CustomerContact.of("cus_8F2A91", "maria@example.com", null, null)));
        when(emailSenderPort.sendEmail(eq("maria@example.com"), eq(notification)))
                .thenReturn(EmailSenderPort.EmailSendResult.ok("prov-123", 120L));

        orchestrator.process(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(notification.getAttempts()).hasSize(1);
        assertThat(notification.getAttempts().get(0).outcome()).isEqualTo("SUCCESS");

        verify(notificationRepository).save(notification);
        // Verify both SENT and DELIVERED audit records were appended (Invariant 4)
        verify(auditLogPort, times(2)).append(any());
    }

    @Test
    @DisplayName("Should fail delivery if customer contact has no email destination")
    void shouldFailWhenNoEmailDestination() {
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(contactRepository.findByCustomerId(notification.getCustomerId()))
                .thenReturn(Optional.of(CustomerContact.of("cus_8F2A91", null, null, null)));

        orchestrator.process(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getFailureReason()).isEqualTo(FailureReason.INVALID_DESTINATION);

        verify(emailSenderPort, never()).sendEmail(any(), any());
        verify(notificationRepository).save(notification);
        verify(auditLogPort).append(any());
    }

    @Test
    @DisplayName("Should record failed attempt and mark notification FAILED when email provider fails")
    void shouldHandleEmailSendFailure() {
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));
        when(contactRepository.findByCustomerId(notification.getCustomerId()))
                .thenReturn(Optional.of(CustomerContact.of("cus_8F2A91", "maria@example.com", null, null)));
        when(emailSenderPort.sendEmail(eq("maria@example.com"), eq(notification)))
                .thenReturn(EmailSenderPort.EmailSendResult.error("SMTP_550_REJECTED", 250L));

        orchestrator.process(notification.getId());

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getFailureReason()).isEqualTo(FailureReason.PROVIDER_UNAVAILABLE);
        assertThat(notification.getAttempts()).hasSize(1);
        assertThat(notification.getAttempts().get(0).outcome()).isEqualTo("FAILURE");

        verify(notificationRepository).save(notification);
        verify(auditLogPort).append(any());
    }
}
