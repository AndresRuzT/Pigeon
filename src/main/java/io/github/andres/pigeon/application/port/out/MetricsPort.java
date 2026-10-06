package io.github.andres.pigeon.application.port.out;

import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.domain.enums.NotificationStatus;
import io.github.andres.pigeon.domain.enums.Priority;

import java.time.Duration;

public interface MetricsPort {

    void recordNotificationAccepted(EventType eventType, Priority priority);

    void recordDuplicateNotification();

    void recordDeliveryAttempt(Channel channel, String outcome);

    void recordDeliveryLatency(Channel channel, Priority priority, Duration duration);

    void recordNotificationFinal(NotificationStatus status, String reason);

    void recordFallbackTriggered(Channel fromChannel, Channel toChannel);

    void recordRateLimitRejected(String bucket);
}
