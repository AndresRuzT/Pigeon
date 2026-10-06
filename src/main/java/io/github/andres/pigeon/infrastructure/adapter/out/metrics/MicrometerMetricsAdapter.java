package io.github.andres.pigeon.infrastructure.adapter.out.metrics;

import io.github.andres.pigeon.application.port.out.MetricsPort;
import io.github.andres.pigeon.application.port.out.OutboxRepository;
import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.domain.enums.NotificationStatus;
import io.github.andres.pigeon.domain.enums.Priority;
import io.github.andres.pigeon.infrastructure.config.RabbitConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Properties;

@Component
public class MicrometerMetricsAdapter implements MetricsPort {

    private final MeterRegistry meterRegistry;

    public MicrometerMetricsAdapter(
            MeterRegistry meterRegistry,
            OutboxRepository outboxRepository,
            @Autowired(required = false) CircuitBreakerRegistry circuitBreakerRegistry,
            @Autowired(required = false) RabbitAdmin rabbitAdmin
    ) {
        this.meterRegistry = meterRegistry;

        // Register Outbox pending gauge
        Gauge.builder("pigeon.outbox.pending", outboxRepository, OutboxRepository::countUnpublished)
                .description("Number of unpublished outbox messages pending relay")
                .register(meterRegistry);

        // Register CircuitBreaker state gauges
        if (circuitBreakerRegistry != null) {
            for (CircuitBreaker cb : circuitBreakerRegistry.getAllCircuitBreakers()) {
                Gauge.builder("pigeon.circuitbreaker.state", cb, c -> mapCircuitBreakerState(c.getState()))
                        .tag("channel", cb.getName())
                        .description("Circuit breaker state (0=CLOSED, 1=HALF_OPEN, 2=OPEN, 3=OTHER)")
                        .register(meterRegistry);
            }
        }

        // Register Queue depth gauges
        if (rabbitAdmin != null) {
            List<String> queues = List.of(
                    RabbitConfig.QUEUE_EVENTS_LOW,
                    RabbitConfig.QUEUE_EVENTS_HIGH,
                    RabbitConfig.QUEUE_INBOUND,
                    RabbitConfig.QUEUE_EXPIRED,
                    RabbitConfig.QUEUE_DLQ
            );
            for (String queue : queues) {
                Gauge.builder("pigeon.queue.depth", rabbitAdmin, admin -> getQueueDepth(admin, queue))
                        .tag("queue", queue)
                        .description("Current message depth for queue")
                        .register(meterRegistry);
            }
        }
    }

    private static double mapCircuitBreakerState(CircuitBreaker.State state) {
        return switch (state) {
            case CLOSED -> 0.0;
            case HALF_OPEN -> 1.0;
            case OPEN -> 2.0;
            default -> 3.0;
        };
    }

    private static double getQueueDepth(RabbitAdmin admin, String queueName) {
        try {
            Properties props = admin.getQueueProperties(queueName);
            if (props != null && props.containsKey(RabbitAdmin.QUEUE_MESSAGE_COUNT)) {
                Object count = props.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
                if (count instanceof Number num) {
                    return num.doubleValue();
                }
            }
        } catch (Exception ignored) {
            // Broker may be unavailable during startup/tests
        }
        return 0.0;
    }

    @Override
    public void recordNotificationAccepted(EventType eventType, Priority priority) {
        meterRegistry.counter("pigeon.notifications.accepted",
                "eventType", eventType.name(),
                "priority", priority.name()
        ).increment();
    }

    @Override
    public void recordDuplicateNotification() {
        meterRegistry.counter("pigeon.notifications.duplicates").increment();
    }

    @Override
    public void recordDeliveryAttempt(Channel channel, String outcome) {
        meterRegistry.counter("pigeon.delivery.attempts",
                "channel", channel.name(),
                "outcome", outcome
        ).increment();
    }

    @Override
    public void recordDeliveryLatency(Channel channel, Priority priority, Duration duration) {
        Timer.builder("pigeon.delivery.latency")
                .tag("channel", channel.name())
                .tag("priority", priority.name())
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(duration);
    }

    @Override
    public void recordNotificationFinal(NotificationStatus status, String reason) {
        meterRegistry.counter("pigeon.notifications.final",
                "status", status.name(),
                "reason", reason != null ? reason : "NONE"
        ).increment();
    }

    @Override
    public void recordFallbackTriggered(Channel fromChannel, Channel toChannel) {
        meterRegistry.counter("pigeon.fallback.triggered",
                "from", fromChannel.name(),
                "to", toChannel.name()
        ).increment();
    }

    @Override
    public void recordRateLimitRejected(String bucket) {
        meterRegistry.counter("pigeon.ratelimit.rejected",
                "bucket", bucket
        ).increment();
    }
}
