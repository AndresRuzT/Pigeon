package io.github.andres.pigeon.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.andres.pigeon.application.port.out.AuditLogPort;
import io.github.andres.pigeon.application.port.out.ClockPort;
import io.github.andres.pigeon.application.port.out.NotificationRepository;
import io.github.andres.pigeon.domain.enums.FailureReason;
import io.github.andres.pigeon.domain.model.AuditRecord;
import io.github.andres.pigeon.domain.model.Notification;
import io.github.andres.pigeon.infrastructure.config.RabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Listens on pigeon.expired queue where high-priority messages (e.g. OTPs) dead-letter
 * when their queue TTL expires. Marks the notification as FAILED with EXPIRED reason.
 */
@Component
public class RabbitExpiredListener {

    private static final Logger log = LoggerFactory.getLogger(RabbitExpiredListener.class);

    private final NotificationRepository notificationRepository;
    private final AuditLogPort auditLogPort;
    private final ClockPort clockPort;
    private final ObjectMapper objectMapper;
    private final io.github.andres.pigeon.application.port.out.MetricsPort metricsPort;

    public RabbitExpiredListener(
            NotificationRepository notificationRepository,
            AuditLogPort auditLogPort,
            ClockPort clockPort,
            ObjectMapper objectMapper,
            io.github.andres.pigeon.application.port.out.MetricsPort metricsPort
    ) {
        this.notificationRepository = notificationRepository;
        this.auditLogPort = auditLogPort;
        this.clockPort = clockPort;
        this.objectMapper = objectMapper;
        this.metricsPort = metricsPort;
    }

    @RabbitListener(queues = RabbitConfig.QUEUE_EXPIRED)
    @Transactional
    public void onExpiredMessage(Message rawMessage) {
        String payload = new String(rawMessage.getBody(), StandardCharsets.UTF_8);
        try {
            JsonNode root = objectMapper.readTree(payload);
            String notificationIdStr = root.path("notificationId").asText();

            if (notificationIdStr == null || notificationIdStr.isBlank()) {
                log.warn("Received expired message with missing notificationId: {}", payload);
                return;
            }

            UUID notificationId = UUID.fromString(notificationIdStr);
            Instant now = clockPort.now();

            Optional<Notification> notificationOpt = notificationRepository.findById(notificationId);
            if (notificationOpt.isPresent()) {
                Notification notification = notificationOpt.get();
                if (!notification.isTerminal()) {
                    AuditRecord expiredAudit = notification.markFailed(
                            FailureReason.EXPIRED,
                            null,
                            "rabbitmq-ttl-expiry",
                            null,
                            "High priority TTL expired before consumer could process message",
                            now
                    );
                    notificationRepository.save(notification);
                    auditLogPort.append(expiredAudit);
                    metricsPort.recordNotificationFinal(io.github.andres.pigeon.domain.enums.NotificationStatus.FAILED, FailureReason.EXPIRED.name());
                    log.warn("Marked notification {} as FAILED (EXPIRED) due to high-priority TTL expiration.", notificationId);
                }
            }
        } catch (Exception ex) {
            log.error("Error processing expired message from queue: {}", payload, ex);
        }
    }
}
