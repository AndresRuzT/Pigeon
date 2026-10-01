package io.github.andres.pigeon.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.andres.pigeon.application.port.in.ProcessNotificationUseCase;
import io.github.andres.pigeon.infrastructure.config.RabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class RabbitNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(RabbitNotificationListener.class);

    private final ProcessNotificationUseCase processNotificationUseCase;
    private final ObjectMapper objectMapper;

    public RabbitNotificationListener(ProcessNotificationUseCase processNotificationUseCase, ObjectMapper objectMapper) {
        this.processNotificationUseCase = processNotificationUseCase;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = {RabbitConfig.QUEUE_EVENTS_LOW, RabbitConfig.QUEUE_EVENTS_HIGH})
    public void onMessage(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            String notificationIdStr = root.path("notificationId").asText();
            if (notificationIdStr == null || notificationIdStr.isBlank()) {
                log.error("Received poison envelope without notificationId: {}", payload);
                return;
            }

            UUID notificationId = UUID.fromString(notificationIdStr);
            log.info("Received notification delivery task from queue for id: {}", notificationId);
            processNotificationUseCase.process(notificationId);
        } catch (Exception e) {
            log.error("Failed to process message from queue: {}", payload, e);
            throw new RuntimeException("Error processing notification queue message", e);
        }
    }
}
