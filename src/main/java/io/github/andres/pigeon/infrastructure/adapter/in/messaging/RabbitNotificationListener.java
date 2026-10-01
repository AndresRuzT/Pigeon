package io.github.andres.pigeon.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.andres.pigeon.application.port.in.ProcessNotificationUseCase;
import io.github.andres.pigeon.infrastructure.config.RabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class RabbitNotificationListener {

    private static final Logger log = LoggerFactory.getLogger(RabbitNotificationListener.class);

    private final ProcessNotificationUseCase processNotificationUseCase;
    private final RabbitOperations rabbitTemplate;
    private final ObjectMapper objectMapper;

    public RabbitNotificationListener(
            ProcessNotificationUseCase processNotificationUseCase,
            RabbitOperations rabbitTemplate,
            ObjectMapper objectMapper
    ) {
        this.processNotificationUseCase = processNotificationUseCase;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = {RabbitConfig.QUEUE_EVENTS_LOW, RabbitConfig.QUEUE_EVENTS_HIGH})
    public void onMessage(Message rawMessage) {
        String payload = new String(rawMessage.getBody(), StandardCharsets.UTF_8);
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception ex) {
            log.error("POISON MESSAGE: Unparseable JSON envelope detected. Routing directly to DLQ: {}", payload, ex);
            sendToDlq(payload, "POISON_MESSAGE", ex.getMessage());
            return;
        }

        String notificationIdStr = root.path("notificationId").asText();
        if (notificationIdStr == null || notificationIdStr.isBlank()) {
            log.error("POISON MESSAGE: Envelope missing notificationId. Routing directly to DLQ: {}", payload);
            sendToDlq(payload, "POISON_MESSAGE", "Missing notificationId");
            return;
        }

        UUID notificationId;
        try {
            notificationId = UUID.fromString(notificationIdStr);
        } catch (IllegalArgumentException ex) {
            log.error("POISON MESSAGE: Invalid UUID format for notificationId. Routing directly to DLQ: {}", notificationIdStr);
            sendToDlq(payload, "POISON_MESSAGE", "Invalid UUID format: " + notificationIdStr);
            return;
        }

        int currentAttempt = root.path("attempt").asInt(1);
        String priority = root.path("priority").asText("LOW");

        try {
            log.info("Processing notification {} (attempt={}, priority={})", notificationId, currentAttempt, priority);
            processNotificationUseCase.process(notificationId);
        } catch (Exception ex) {
            log.error("Failure processing notification {}: {}", notificationId, ex.getMessage(), ex);
            handleProcessingFailure(root, currentAttempt, priority, ex.getMessage());
        }
    }

    private void handleProcessingFailure(JsonNode root, int currentAttempt, String priority, String errorDetail) {
        // High priority messages do not enter the delayed ladder to prevent late delivery
        if ("HIGH".equalsIgnoreCase(priority)) {
            log.warn("HIGH priority notification failed, sending directly to DLQ (no delayed ladder)");
            sendToDlq(root.toString(), "HIGH_PRIORITY_FAILURE", errorDetail);
            return;
        }

        // Tier 2: Delayed retry ladder for LOW priority
        if (root instanceof ObjectNode objNode) {
            int nextAttempt = currentAttempt + 1;
            objNode.put("attempt", nextAttempt);
            String nextPayload = objNode.toString();

            if (currentAttempt == 1) {
                log.info("Routing failed LOW notification to delayed retry queue: 30s ladder");
                rabbitTemplate.convertAndSend(RabbitConfig.RETRY_EXCHANGE, RabbitConfig.ROUTING_KEY_RETRY_30S, nextPayload);
            } else if (currentAttempt == 2) {
                log.info("Routing failed LOW notification to delayed retry queue: 2m ladder");
                rabbitTemplate.convertAndSend(RabbitConfig.RETRY_EXCHANGE, RabbitConfig.ROUTING_KEY_RETRY_2M, nextPayload);
            } else if (currentAttempt == 3) {
                log.info("Routing failed LOW notification to delayed retry queue: 10m ladder");
                rabbitTemplate.convertAndSend(RabbitConfig.RETRY_EXCHANGE, RabbitConfig.ROUTING_KEY_RETRY_10M, nextPayload);
            } else {
                log.error("Retry ladder exhausted (attempt={}). Routing to DLQ.", currentAttempt);
                sendToDlq(nextPayload, "RETRY_LADDER_EXHAUSTED", errorDetail);
            }
        } else {
            sendToDlq(root.toString(), "PROCESSING_FAILURE", errorDetail);
        }
    }

    private void sendToDlq(String payload, String reason, String detail) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding("UTF-8");
        properties.setHeader("x-failure-reason", reason);
        if (detail != null) {
            properties.setHeader("x-exception-message", detail);
        }

        Message message = new Message(payload.getBytes(StandardCharsets.UTF_8), properties);
        rabbitTemplate.send(RabbitConfig.DLX_EXCHANGE, RabbitConfig.ROUTING_KEY_DEAD, message);
    }
}
