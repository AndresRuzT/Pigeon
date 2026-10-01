package io.github.andres.pigeon.domain;

import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.domain.enums.FailureReason;
import io.github.andres.pigeon.domain.enums.NotificationStatus;
import io.github.andres.pigeon.domain.enums.Priority;
import io.github.andres.pigeon.domain.exception.InvalidStateTransitionException;
import io.github.andres.pigeon.domain.model.AuditRecord;
import io.github.andres.pigeon.domain.model.Notification;
import io.github.andres.pigeon.domain.vo.CustomerId;
import io.github.andres.pigeon.domain.vo.IdempotencyKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationTest {

    private Instant now;
    private Notification notification;

    @BeforeEach
    void setUp() {
        now = Instant.parse("2026-10-01T12:00:00Z");
        notification = Notification.createPending(
                "bank-client-1",
                IdempotencyKey.of("key-12345"),
                "hash-abc-123",
                CustomerId.of("cus_8F2A91"),
                EventType.TRANSFER_COMPLETED,
                "es",
                Map.of("amount", "100.00", "currency", "USD"),
                now
        );
    }

    @Test
    @DisplayName("Should create notification with initial status PENDING and correct priority")
    void shouldCreateNotificationWithPendingStatus() {
        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(notification.getPriority()).isEqualTo(Priority.LOW);
        assertThat(notification.getAttempts()).isEmpty();
        assertThat(notification.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("Should transition from PENDING to SENT and record attempt and audit record")
    void shouldTransitionPendingToSent() {
        AuditRecord audit = notification.markSent(Channel.EMAIL, "prov-ref-999", 150L, "worker", "corr-1", now.plusSeconds(5));

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(notification.getAttempts()).hasSize(1);
        assertThat(notification.getAttempts().get(0).outcome()).isEqualTo("SUCCESS");
        assertThat(notification.getAttempts().get(0).providerRef()).isEqualTo("prov-ref-999");

        assertThat(audit.fromStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(audit.toStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(audit.channel()).isEqualTo(Channel.EMAIL);
    }

    @Test
    @DisplayName("Invariant 2: DELIVERED can only be reached from SENT")
    void deliveredCanOnlyBeReachedFromSent() {
        // Direct transition from PENDING to DELIVERED must fail
        assertThatThrownBy(() -> notification.markDelivered(Channel.EMAIL, "worker", "corr-1", now))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("Illegal state transition from PENDING to DELIVERED");

        // Transition PENDING -> SENT -> DELIVERED must succeed
        notification.markSent(Channel.EMAIL, "prov-ref-1", 100L, "worker", "corr-1", now);
        AuditRecord deliveredAudit = notification.markDelivered(Channel.EMAIL, "worker", "corr-1", now.plusSeconds(2));

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.DELIVERED);
        assertThat(notification.isTerminal()).isTrue();
        assertThat(deliveredAudit.fromStatus()).isEqualTo(NotificationStatus.SENT);
        assertThat(deliveredAudit.toStatus()).isEqualTo(NotificationStatus.DELIVERED);
    }

    @Test
    @DisplayName("Invariant 1: DELIVERED and FAILED are terminal; no transition leaves them")
    void terminalStatusesCannotTransition() {
        notification.markSent(Channel.EMAIL, "prov-ref-1", 100L, "worker", "corr-1", now);
        notification.markDelivered(Channel.EMAIL, "worker", "corr-1", now);

        // Attempt to transition from DELIVERED to FAILED
        assertThatThrownBy(() -> notification.markFailed(FailureReason.PROVIDER_UNAVAILABLE, Channel.EMAIL, "worker", "corr-1", "err", now))
                .isInstanceOf(InvalidStateTransitionException.class);

        // Attempt to transition from DELIVERED to SENT
        assertThatThrownBy(() -> notification.markSent(Channel.EMAIL, "prov-ref-2", 100L, "worker", "corr-1", now))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    @DisplayName("Should transition from PENDING to FAILED with FailureReason and audit record")
    void shouldTransitionToFailed() {
        AuditRecord audit = notification.markFailed(
                FailureReason.ALL_CHANNELS_EXHAUSTED,
                Channel.EMAIL,
                "system",
                "corr-fail",
                "All channels exhausted",
                now
        );

        assertThat(notification.getStatus()).isEqualTo(NotificationStatus.FAILED);
        assertThat(notification.getFailureReason()).isEqualTo(FailureReason.ALL_CHANNELS_EXHAUSTED);
        assertThat(notification.isTerminal()).isTrue();
        assertThat(audit.fromStatus()).isEqualTo(NotificationStatus.PENDING);
        assertThat(audit.toStatus()).isEqualTo(NotificationStatus.FAILED);
    }

    @Test
    @DisplayName("Invariant 3: Delivery attempts are append-only")
    void deliveryAttemptsAreAppendOnly() {
        notification.recordFailedAttempt(Channel.EMAIL, "TIMEOUT", 2000L, now);
        notification.recordFailedAttempt(Channel.SMS, "500_SERVER_ERROR", 1500L, now.plusSeconds(1));

        assertThat(notification.getAttempts()).hasSize(2);
        assertThat(notification.getAttempts().get(0).attemptNo()).isEqualTo(1);
        assertThat(notification.getAttempts().get(1).attemptNo()).isEqualTo(2);

        // Attempts list must be unmodifiable
        assertThatThrownBy(() -> notification.getAttempts().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
