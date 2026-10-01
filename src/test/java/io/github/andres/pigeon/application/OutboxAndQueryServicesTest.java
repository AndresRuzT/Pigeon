package io.github.andres.pigeon.application;

import io.github.andres.pigeon.application.port.out.AuditLogPort;
import io.github.andres.pigeon.application.port.out.ClockPort;
import io.github.andres.pigeon.application.port.out.MessagePublisher;
import io.github.andres.pigeon.application.port.out.NotificationRepository;
import io.github.andres.pigeon.application.port.out.OutboxRepository;
import io.github.andres.pigeon.application.service.OutboxRelayService;
import io.github.andres.pigeon.application.service.QueryNotificationService;
import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.domain.model.AuditRecord;
import io.github.andres.pigeon.domain.model.Notification;
import io.github.andres.pigeon.domain.vo.CustomerId;
import io.github.andres.pigeon.domain.vo.IdempotencyKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxAndQueryServicesTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private MessagePublisher messagePublisher;

    @Mock
    private ClockPort clockPort;

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private AuditLogPort auditLogPort;

    @Test
    @DisplayName("OutboxRelayService should poll and publish messages then mark published")
    void shouldRelayOutboxMessages() {
        Instant now = Instant.parse("2026-10-01T12:00:00Z");
        when(clockPort.now()).thenReturn(now);

        OutboxRelayService relayService = new OutboxRelayService(outboxRepository, messagePublisher, clockPort);

        UUID msgId = UUID.randomUUID();
        OutboxRepository.OutboxMessage msg = new OutboxRepository.OutboxMessage(
                msgId,
                UUID.randomUUID(),
                "TRANSFER_COMPLETED",
                "low",
                "{\"notificationId\":\"abc\"}",
                now.minusSeconds(10),
                null,
                0
        );

        when(outboxRepository.lockNextBatch(50)).thenReturn(List.of(msg));

        relayService.relayMessages();

        verify(messagePublisher).publish("low", "{\"notificationId\":\"abc\"}");
        verify(outboxRepository).markPublished(msgId, now);
    }

    @Test
    @DisplayName("OutboxRelayService should handle publisher failure gracefully without throwing")
    void shouldHandlePublishFailureGracefully() {
        OutboxRelayService relayService = new OutboxRelayService(outboxRepository, messagePublisher, clockPort);

        UUID msgId = UUID.randomUUID();
        OutboxRepository.OutboxMessage msg = new OutboxRepository.OutboxMessage(
                msgId,
                UUID.randomUUID(),
                "TRANSFER_COMPLETED",
                "low",
                "{\"notificationId\":\"abc\"}",
                Instant.now(),
                null,
                0
        );

        when(outboxRepository.lockNextBatch(50)).thenReturn(List.of(msg));
        doThrow(new RuntimeException("RabbitMQ connection refused")).when(messagePublisher).publish(any(), any());

        relayService.relayMessages();

        verify(messagePublisher).publish("low", "{\"notificationId\":\"abc\"}");
    }

    @Test
    @DisplayName("QueryNotificationService should retrieve notification and audit trail")
    void shouldQueryNotificationAndAuditTrail() {
        QueryNotificationService queryService = new QueryNotificationService(notificationRepository, auditLogPort);

        UUID id = UUID.randomUUID();
        Notification notification = Notification.createPending(
                "client",
                IdempotencyKey.of("k1"),
                "h1",
                CustomerId.of("cus_1"),
                EventType.TRANSFER_COMPLETED,
                "en",
                Map.of(),
                Instant.now()
        );

        when(notificationRepository.findById(id)).thenReturn(Optional.of(notification));
        when(auditLogPort.findByNotificationId(id)).thenReturn(List.of());

        Notification result = queryService.getNotification(id);
        List<AuditRecord> audits = queryService.getAuditTrail(id);

        assertThat(result).isNotNull();
        assertThat(audits).isEmpty();
        verify(notificationRepository).findById(id);
        verify(auditLogPort).findByNotificationId(id);
    }
}
