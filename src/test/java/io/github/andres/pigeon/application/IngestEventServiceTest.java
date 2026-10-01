package io.github.andres.pigeon.application;

import io.github.andres.pigeon.application.port.in.IngestEventCommand;
import io.github.andres.pigeon.application.port.out.AuditLogPort;
import io.github.andres.pigeon.application.port.out.ClockPort;
import io.github.andres.pigeon.application.port.out.NotificationRepository;
import io.github.andres.pigeon.application.port.out.OutboxRepository;
import io.github.andres.pigeon.application.service.IngestEventService;
import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.domain.enums.Priority;
import io.github.andres.pigeon.domain.exception.DuplicateEventException;
import io.github.andres.pigeon.domain.model.Notification;
import io.github.andres.pigeon.domain.vo.CustomerId;
import io.github.andres.pigeon.domain.vo.IdempotencyKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IngestEventServiceTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private AuditLogPort auditLogPort;

    @Mock
    private ClockPort clockPort;

    private IngestEventService service;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-10-01T12:00:00Z");
        org.mockito.Mockito.lenient().when(clockPort.now()).thenReturn(now);
        service = new IngestEventService(notificationRepository, outboxRepository, auditLogPort, clockPort);
    }

    @Test
    @DisplayName("Should successfully ingest new event, saving notification, audit log and outbox entry")
    void shouldIngestNewEvent() {
        IngestEventCommand command = new IngestEventCommand(
                "client-bank",
                "key-123",
                "hash-abc",
                "cus_8F2A91",
                EventType.TRANSFER_COMPLETED,
                "es",
                now,
                Map.of("amount", "250.00", "currency", "USD")
        );

        when(notificationRepository.findByClientIdAndIdempotencyKey("client-bank", IdempotencyKey.of("key-123")))
                .thenReturn(Optional.empty());
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        IngestEventCommand.IngestResult result = service.ingest(command);

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.priority()).isEqualTo(Priority.LOW);
        assertThat(result.isReplay()).isFalse();

        verify(notificationRepository).save(any(Notification.class));
        verify(auditLogPort).append(any());
        verify(outboxRepository).save(any());
    }

    @Test
    @DisplayName("Should replay existing notification with same idempotency key and same payload hash")
    void shouldReplayExistingNotification() {
        Notification existing = Notification.createPending(
                "client-bank",
                IdempotencyKey.of("key-123"),
                "hash-abc",
                CustomerId.of("cus_8F2A91"),
                EventType.TRANSFER_COMPLETED,
                "es",
                Map.of(),
                now
        );

        when(notificationRepository.findByClientIdAndIdempotencyKey("client-bank", IdempotencyKey.of("key-123")))
                .thenReturn(Optional.of(existing));

        IngestEventCommand command = new IngestEventCommand(
                "client-bank",
                "key-123",
                "hash-abc",
                "cus_8F2A91",
                EventType.TRANSFER_COMPLETED,
                "es",
                now,
                Map.of()
        );

        IngestEventCommand.IngestResult result = service.ingest(command);

        assertThat(result.notificationId()).isEqualTo(existing.getId());
        assertThat(result.isReplay()).isTrue();
    }

    @Test
    @DisplayName("Should throw DuplicateEventException when idempotency key is reused with different payload hash")
    void shouldThrowWhenHashDiffers() {
        Notification existing = Notification.createPending(
                "client-bank",
                IdempotencyKey.of("key-123"),
                "hash-abc",
                CustomerId.of("cus_8F2A91"),
                EventType.TRANSFER_COMPLETED,
                "es",
                Map.of(),
                now
        );

        when(notificationRepository.findByClientIdAndIdempotencyKey("client-bank", IdempotencyKey.of("key-123")))
                .thenReturn(Optional.of(existing));

        IngestEventCommand command = new IngestEventCommand(
                "client-bank",
                "key-123",
                "hash-DIFFERENT",
                "cus_8F2A91",
                EventType.TRANSFER_COMPLETED,
                "es",
                now,
                Map.of()
        );

        assertThatThrownBy(() -> service.ingest(command))
                .isInstanceOf(DuplicateEventException.class)
                .hasMessageContaining("already been used with a different payload");
    }
}
