package io.github.andres.pigeon.application;

import io.github.andres.pigeon.application.port.in.ProcessWebhookReceiptUseCase;
import io.github.andres.pigeon.application.port.out.AuditLogPort;
import io.github.andres.pigeon.application.port.out.ClockPort;
import io.github.andres.pigeon.application.port.out.MetricsPort;
import io.github.andres.pigeon.application.port.out.NotificationRepository;
import io.github.andres.pigeon.application.service.ProcessWebhookReceiptService;
import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.domain.enums.NotificationStatus;
import io.github.andres.pigeon.domain.exception.InvalidStateTransitionException;
import io.github.andres.pigeon.domain.exception.NotificationNotFoundException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProcessWebhookReceiptServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private AuditLogPort auditLogPort;

    @Mock
    private MetricsPort metricsPort;

    @Mock
    private ClockPort clockPort;

    private ProcessWebhookReceiptService service;
    private Instant now;
    private Notification notification;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-10-05T12:00:00Z");
        org.mockito.Mockito.lenient().when(clockPort.now()).thenReturn(now);

        service = new ProcessWebhookReceiptService(
                notificationRepository,
                auditLogPort,
                metricsPort,
                clockPort
        );

        notification = Notification.createPending(
                "bank-client",
                IdempotencyKey.of("key-wh-1"),
                "hash-wh-1",
                CustomerId.of("cus_8F2A91"),
                EventType.TRANSFER_COMPLETED,
                "es",
                Map.of(),
                now.minusSeconds(10)
        );
        // Put notification in SENT state
        notification.markSent(Channel.SMS, "provider_ref_123", 25L, "system", null, now.minusSeconds(5));
    }

    @Test
    @DisplayName("Should successfully process DELIVERED receipt for notification in SENT status")
    void shouldProcessDeliveredReceipt() {
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));

        var command = new ProcessWebhookReceiptUseCase.ReceiptCommand(
                Channel.SMS,
                notification.getId(),
                "provider_ref_123",
                NotificationStatus.DELIVERED,
                null,
                "Delivered to handset",
                now
        );

        var result = service.processReceipt(command);

        assertThat(result.processed()).isTrue();
        assertThat(result.status()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DELIVERED);

        verify(notificationRepository).save(notification);
        verify(auditLogPort).append(any());
        verify(metricsPort).recordNotificationFinal(NotificationStatus.DELIVERED, null);
    }

    @Test
    @DisplayName("Should successfully process FAILED receipt for notification in SENT status")
    void shouldProcessFailedReceipt() {
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));

        var command = new ProcessWebhookReceiptUseCase.ReceiptCommand(
                Channel.SMS,
                notification.getId(),
                "provider_ref_123",
                NotificationStatus.FAILED,
                "UNDELIVERABLE",
                "Carrier unreachable",
                now
        );

        var result = service.processReceipt(command);

        assertThat(result.processed()).isTrue();
        assertThat(result.status()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);

        verify(notificationRepository).save(notification);
        verify(auditLogPort).append(any());
        verify(metricsPort).recordNotificationFinal(eq(NotificationStatus.FAILED), any());
    }

    @Test
    @DisplayName("Should idempotently return without reprocessing if notification already terminal")
    void shouldIgnoreDuplicateReceiptIfTerminal() {
        notification.markDelivered(Channel.SMS, "system", "initial delivery", now);
        when(notificationRepository.findById(notification.getId())).thenReturn(Optional.of(notification));

        var command = new ProcessWebhookReceiptUseCase.ReceiptCommand(
                Channel.SMS,
                notification.getId(),
                "provider_ref_123",
                NotificationStatus.DELIVERED,
                null,
                "Duplicate callback",
                now
        );

        var result = service.processReceipt(command);

        assertThat(result.processed()).isFalse();
        assertThat(result.status()).isEqualTo(NotificationStatus.DELIVERED);
        verify(notificationRepository, never()).save(any());
        verify(auditLogPort, never()).append(any());
    }

    @Test
    @DisplayName("Should throw InvalidStateTransitionException if notification is not in SENT status")
    void shouldThrowIfNotificationNotInSentState() {
        Notification pendingNotification = Notification.createPending(
                "bank-client",
                IdempotencyKey.of("key-wh-2"),
                "hash-wh-2",
                CustomerId.of("cus_8F2A91"),
                EventType.TRANSFER_COMPLETED,
                "es",
                Map.of(),
                now
        );
        when(notificationRepository.findById(pendingNotification.getId())).thenReturn(Optional.of(pendingNotification));

        var command = new ProcessWebhookReceiptUseCase.ReceiptCommand(
                Channel.SMS,
                pendingNotification.getId(),
                "provider_ref_123",
                NotificationStatus.DELIVERED,
                null,
                "Delivered prematurely",
                now
        );

        assertThatThrownBy(() -> service.processReceipt(command))
                .isInstanceOf(InvalidStateTransitionException.class);

        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should throw NotificationNotFoundException when notification ID does not exist")
    void shouldThrowWhenNotificationNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(notificationRepository.findById(unknownId)).thenReturn(Optional.empty());

        var command = new ProcessWebhookReceiptUseCase.ReceiptCommand(
                Channel.SMS,
                unknownId,
                "provider_ref_unknown",
                NotificationStatus.DELIVERED,
                null,
                "Delivered",
                now
        );

        assertThatThrownBy(() -> service.processReceipt(command))
                .isInstanceOf(NotificationNotFoundException.class);
    }
}
