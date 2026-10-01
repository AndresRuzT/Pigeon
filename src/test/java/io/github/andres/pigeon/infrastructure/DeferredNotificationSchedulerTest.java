package io.github.andres.pigeon.infrastructure;

import io.github.andres.pigeon.application.port.out.AuditLogPort;
import io.github.andres.pigeon.application.port.out.ClockPort;
import io.github.andres.pigeon.application.port.out.NotificationRepository;
import io.github.andres.pigeon.application.port.out.OutboxRepository;
import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.domain.enums.NotificationStatus;
import io.github.andres.pigeon.domain.model.Notification;
import io.github.andres.pigeon.domain.vo.CustomerId;
import io.github.andres.pigeon.domain.vo.IdempotencyKey;
import io.github.andres.pigeon.infrastructure.adapter.out.scheduler.DeferredNotificationScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeferredNotificationSchedulerTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private AuditLogPort auditLogPort;

    @Mock
    private ClockPort clockPort;

    private DeferredNotificationScheduler scheduler;
    private Instant now;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-10-05T08:00:00Z");
        when(clockPort.now()).thenReturn(now);
        scheduler = new DeferredNotificationScheduler(notificationRepository, outboxRepository, auditLogPort, clockPort);
    }

    @Test
    @DisplayName("Should find due deferred notifications, resume them to PENDING and publish outbox event")
    void shouldResumeDeferredNotifications() {
        Notification deferred = Notification.createDeferred(
                "client-1",
                IdempotencyKey.of("key-def"),
                "hash-def",
                CustomerId.of("cus_8F2A91"),
                EventType.TRANSFER_COMPLETED,
                "en",
                Map.of(),
                now.minusSeconds(10),
                now.minusSeconds(3600)
        );

        when(notificationRepository.findDueDeferred(eq(now), eq(50))).thenReturn(List.of(deferred));

        scheduler.resumeDeferredNotifications();

        assertThat(deferred.getStatus()).isEqualTo(NotificationStatus.PENDING);
        verify(notificationRepository).save(deferred);
        verify(auditLogPort).append(any());
        verify(outboxRepository).save(any());
    }
}
