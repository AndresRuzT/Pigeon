package io.github.andres.pigeon.infrastructure;

import io.github.andres.pigeon.application.port.out.OutboxRepository;
import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.domain.enums.NotificationStatus;
import io.github.andres.pigeon.domain.enums.Priority;
import io.github.andres.pigeon.infrastructure.adapter.out.metrics.MicrometerMetricsAdapter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MicrometerMetricsAdapterTest {

    private MeterRegistry meterRegistry;

    @Mock
    private OutboxRepository outboxRepository;

    private MicrometerMetricsAdapter adapter;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        org.mockito.Mockito.lenient().when(outboxRepository.countUnpublished()).thenReturn(7L);
        adapter = new MicrometerMetricsAdapter(meterRegistry, outboxRepository, null, null);
    }

    @Test
    @DisplayName("Should track outbox pending backlog gauge")
    void shouldTrackOutboxPendingGauge() {
        double pending = meterRegistry.get("pigeon.outbox.pending").gauge().value();
        assertThat(pending).isEqualTo(7.0);
    }

    @Test
    @DisplayName("Should record accepted notification counter")
    void shouldRecordNotificationAccepted() {
        adapter.recordNotificationAccepted(EventType.TRANSFER_COMPLETED, Priority.LOW);
        double count = meterRegistry.get("pigeon.notifications.accepted")
                .tag("eventType", "TRANSFER_COMPLETED")
                .tag("priority", "LOW")
                .counter().count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should record duplicate notification counter")
    void shouldRecordDuplicateNotification() {
        adapter.recordDuplicateNotification();
        double count = meterRegistry.get("pigeon.notifications.duplicates").counter().count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should record delivery attempts by channel and outcome")
    void shouldRecordDeliveryAttempt() {
        adapter.recordDeliveryAttempt(Channel.SMS, "SUCCESS");
        adapter.recordDeliveryAttempt(Channel.PUSH, "FAILED");

        double smsSuccess = meterRegistry.get("pigeon.delivery.attempts")
                .tag("channel", "SMS")
                .tag("outcome", "SUCCESS")
                .counter().count();
        double pushFailed = meterRegistry.get("pigeon.delivery.attempts")
                .tag("channel", "PUSH")
                .tag("outcome", "FAILED")
                .counter().count();

        assertThat(smsSuccess).isEqualTo(1.0);
        assertThat(pushFailed).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should record delivery latency timer")
    void shouldRecordDeliveryLatency() {
        adapter.recordDeliveryLatency(Channel.EMAIL, Priority.LOW, Duration.ofMillis(120));
        long count = meterRegistry.get("pigeon.delivery.latency")
                .tag("channel", "EMAIL")
                .tag("priority", "LOW")
                .timer().count();
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should record final notification terminal status counter")
    void shouldRecordNotificationFinal() {
        adapter.recordNotificationFinal(NotificationStatus.DELIVERED, "NONE");
        double count = meterRegistry.get("pigeon.notifications.final")
                .tag("status", "DELIVERED")
                .tag("reason", "NONE")
                .counter().count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should record fallback triggered counter")
    void shouldRecordFallbackTriggered() {
        adapter.recordFallbackTriggered(Channel.PUSH, Channel.SMS);
        double count = meterRegistry.get("pigeon.fallback.triggered")
                .tag("from", "PUSH")
                .tag("to", "SMS")
                .counter().count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Should record rate limit rejected counter")
    void shouldRecordRateLimitRejected() {
        adapter.recordRateLimitRejected("otp");
        double count = meterRegistry.get("pigeon.ratelimit.rejected")
                .tag("bucket", "otp")
                .counter().count();
        assertThat(count).isEqualTo(1.0);
    }
}
