package io.github.andres.pigeon.application;

import io.github.andres.pigeon.application.port.in.IngestEventCommand;
import io.github.andres.pigeon.application.port.out.AuditLogPort;
import io.github.andres.pigeon.application.port.out.ClockPort;
import io.github.andres.pigeon.application.port.out.CustomerPreferenceRepository;
import io.github.andres.pigeon.application.port.out.IdempotencyStore;
import io.github.andres.pigeon.application.port.out.NotificationRepository;
import io.github.andres.pigeon.application.port.out.OutboxRepository;
import io.github.andres.pigeon.application.port.out.RateLimiterPort;
import io.github.andres.pigeon.application.service.IngestEventService;
import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.domain.enums.Priority;
import io.github.andres.pigeon.domain.exception.DuplicateEventException;
import io.github.andres.pigeon.domain.exception.RateLimitExceededException;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
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

    @Mock
    private IdempotencyStore idempotencyStore;

    @Mock
    private RateLimiterPort rateLimiterPort;

    @Mock
    private CustomerPreferenceRepository customerPreferenceRepository;

    @Mock
    private io.github.andres.pigeon.application.port.out.MetricsPort metricsPort;

    private IngestEventService service;
    private Instant now;

    @BeforeEach
    void setUp() {
        // Monday 2026-10-05 12:00:00 UTC (normal business hours)
        now = Instant.parse("2026-10-05T12:00:00Z");
        org.mockito.Mockito.lenient().when(clockPort.now()).thenReturn(now);
        org.mockito.Mockito.lenient().when(idempotencyStore.acquireOrFind(any(), any(), any(), any()))
                .thenReturn(new IdempotencyStore.AcquisitionResult(IdempotencyStore.LockResult.ACQUIRED, Optional.empty()));
        org.mockito.Mockito.lenient().when(rateLimiterPort.isAllowed(any(), any())).thenReturn(true);
        org.mockito.Mockito.lenient().when(customerPreferenceRepository.findByCustomerId(any()))
                .thenReturn(Optional.of(CustomerPreference.defaultPreference(CustomerId.of("cus_8F2A91"), now)));

        service = new IngestEventService(
                notificationRepository,
                outboxRepository,
                auditLogPort,
                clockPort,
                idempotencyStore,
                rateLimiterPort,
                customerPreferenceRepository,
                metricsPort
        );
    }

    @Test
    @DisplayName("Should successfully ingest new event during business hours")
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
    @DisplayName("Should throw RateLimitExceededException when rate limiter rejects request")
    void shouldThrowWhenRateLimitExceeded() {
        when(rateLimiterPort.isAllowed("cus_8F2A91", EventType.TRANSFER_COMPLETED)).thenReturn(false);

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

        assertThatThrownBy(() -> service.ingest(command))
                .isInstanceOf(RateLimitExceededException.class)
                .hasMessageContaining("Rate limit exceeded for customer cus_8F2A91");

        verify(idempotencyStore).evict(eq("client-bank"), eq(IdempotencyKey.of("key-123")));
        verify(notificationRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should create DEFERRED notification and not publish to outbox when event arrives during quiet hours")
    void shouldDeferDuringQuietHours() {
        // Sunday 2026-10-04 14:00:00 UTC (quiet hours)
        Instant sunday = Instant.parse("2026-10-04T14:00:00Z");
        when(clockPort.now()).thenReturn(sunday);

        IngestEventCommand command = new IngestEventCommand(
                "client-bank",
                "key-quiet",
                "hash-quiet",
                "cus_8F2A91",
                EventType.TRANSFER_COMPLETED,
                "en",
                sunday,
                Map.of("amount", "100.00", "currency", "USD")
        );

        when(notificationRepository.findByClientIdAndIdempotencyKey("client-bank", IdempotencyKey.of("key-quiet")))
                .thenReturn(Optional.empty());
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        IngestEventCommand.IngestResult result = service.ingest(command);

        assertThat(result.status()).isEqualTo("DEFERRED");
        verify(notificationRepository).save(any(Notification.class));
        verify(outboxRepository, never()).save(any());
    }

    @Test
    @DisplayName("Mandatory security message (OTP) ignores quiet hours and is created as PENDING")
    void mandatorySecurityMessageIgnoresQuietHours() {
        // Saturday 2026-10-03 23:00:00 UTC (quiet hours)
        Instant saturday = Instant.parse("2026-10-03T23:00:00Z");
        when(clockPort.now()).thenReturn(saturday);

        IngestEventCommand command = new IngestEventCommand(
                "client-bank",
                "key-otp-quiet",
                "hash-otp-quiet",
                "cus_8F2A91",
                EventType.OTP_REQUESTED,
                "en",
                saturday,
                Map.of("otpCode", "123456", "expiresInSeconds", 120)
        );

        when(notificationRepository.findByClientIdAndIdempotencyKey("client-bank", IdempotencyKey.of("key-otp-quiet")))
                .thenReturn(Optional.empty());
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        IngestEventCommand.IngestResult result = service.ingest(command);

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.priority()).isEqualTo(Priority.HIGH);
        verify(outboxRepository).save(any());
    }

    @Test
    @DisplayName("Should create FAILED notification with SUPPRESSED_OPT_OUT when customer opted out of category")
    void shouldSuppressWhenCustomerOptedOut() {
        CustomerPreference optOutPref = new CustomerPreference(
                CustomerId.of("cus_8F2A91"),
                List.of(Channel.PUSH, Channel.SMS, Channel.EMAIL),
                List.of(Channel.PUSH, Channel.SMS, Channel.EMAIL),
                Set.of("PAYMENT_REMINDER"),
                ZoneId.of("UTC"),
                true,
                now,
                now
        );
        when(customerPreferenceRepository.findByCustomerId(CustomerId.of("cus_8F2A91")))
                .thenReturn(Optional.of(optOutPref));

        IngestEventCommand command = new IngestEventCommand(
                "client-bank",
                "key-optout",
                "hash-optout",
                "cus_8F2A91",
                EventType.PAYMENT_REMINDER,
                "en",
                now,
                Map.of("amount", "50.00", "currency", "USD", "dueDate", "2026-10-20")
        );

        when(notificationRepository.findByClientIdAndIdempotencyKey("client-bank", IdempotencyKey.of("key-optout")))
                .thenReturn(Optional.empty());
        when(notificationRepository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        IngestEventCommand.IngestResult result = service.ingest(command);

        assertThat(result.status()).isEqualTo("FAILED");
        verify(outboxRepository, never()).save(any());
    }

    @Test
    @DisplayName("Should replay existing notification with same idempotency key and same payload hash without consuming rate limit")
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

    @Test
    @DisplayName("Should replay immediately from Redis fast-path when key and hash match without checking rate limit")
    void shouldReplayFromRedisFastPath() {
        UUID notificationId = UUID.randomUUID();
        when(idempotencyStore.acquireOrFind(eq("client-bank"), eq(IdempotencyKey.of("key-fast")), any(), any()))
                .thenReturn(new IdempotencyStore.AcquisitionResult(
                        IdempotencyStore.LockResult.EXISTS,
                        Optional.of(new IdempotencyStore.StoredIdempotency("hash-fast", notificationId))
                ));

        Notification existing = Notification.createPending(
                "client-bank",
                IdempotencyKey.of("key-fast"),
                "hash-fast",
                CustomerId.of("cus_8F2A91"),
                EventType.TRANSFER_COMPLETED,
                "en",
                Map.of(),
                now
        );
        when(notificationRepository.findById(notificationId)).thenReturn(Optional.of(existing));

        IngestEventCommand command = new IngestEventCommand(
                "client-bank",
                "key-fast",
                "hash-fast",
                "cus_8F2A91",
                EventType.TRANSFER_COMPLETED,
                "en",
                now,
                Map.of()
        );

        IngestEventCommand.IngestResult result = service.ingest(command);

        assertThat(result.notificationId()).isEqualTo(existing.getId());
        assertThat(result.isReplay()).isTrue();
        verify(rateLimiterPort, never()).isAllowed(any(), any());
    }
}
