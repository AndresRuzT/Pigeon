package io.github.andres.pigeon.infrastructure.adapter.in.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.andres.pigeon.application.port.in.IngestEventCommand;
import io.github.andres.pigeon.application.port.in.IngestEventUseCase;
import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.infrastructure.adapter.in.rest.dto.IngestEventRequest;
import io.github.andres.pigeon.infrastructure.adapter.in.rest.validation.NoSensitiveDataValidator;
import io.github.andres.pigeon.infrastructure.config.RabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitOperations;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

@Component
public class RabbitInboundEventListener {

    private static final Logger log = LoggerFactory.getLogger(RabbitInboundEventListener.class);

    private final IngestEventUseCase ingestEventUseCase;
    private final RabbitOperations rabbitTemplate;
    private final ObjectMapper objectMapper;
    private final NoSensitiveDataValidator sensitiveDataValidator;

    public RabbitInboundEventListener(
            IngestEventUseCase ingestEventUseCase,
            RabbitOperations rabbitTemplate,
            ObjectMapper objectMapper
    ) {
        this.ingestEventUseCase = ingestEventUseCase;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
        this.sensitiveDataValidator = new NoSensitiveDataValidator();
    }

    @RabbitListener(queues = RabbitConfig.QUEUE_INBOUND)
    public void onInboundEvent(Message rawMessage) {
        String payload = new String(rawMessage.getBody(), StandardCharsets.UTF_8);
        MessageProperties properties = rawMessage.getMessageProperties();

        // 1. Parse JSON payload
        IngestEventRequest request;
        try {
            request = objectMapper.readValue(payload, IngestEventRequest.class);
        } catch (Exception ex) {
            log.error("POISON MESSAGE on inbound queue: malformed JSON: {}", payload, ex);
            sendToDlq(rawMessage, "POISON_MESSAGE", ex.getMessage());
            return;
        }

        // 2. Validate sensitive data
        if (!sensitiveDataValidator.isValid(request, null)) {
            log.error("POISON MESSAGE on inbound queue: sensitive card/account data detected. Routing to DLQ: {}", payload);
            sendToDlq(rawMessage, "SENSITIVE_DATA_DETECTED", "Full card or account numbers detected");
            return;
        }

        // 3. Extract metadata
        String idempotencyKey = (String) properties.getHeaders().get("Idempotency-Key");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            // Check if present in data map or generate fallback
            Object keyFromData = request.data() != null ? request.data().get("idempotencyKey") : null;
            idempotencyKey = keyFromData != null ? keyFromData.toString() : "inbound-" + request.occurredAt() + "-" + request.customerId();
        }

        String clientId = (String) properties.getHeaders().getOrDefault("Client-Id", "queue-inbound-producer");
        String payloadHash = computePayloadHash(request);

        IngestEventCommand command = new IngestEventCommand(
                clientId,
                idempotencyKey,
                payloadHash,
                request.customerId(),
                request.eventType(),
                request.locale() != null ? request.locale() : "en",
                request.occurredAt() != null ? request.occurredAt() : Instant.now(),
                request.data() != null ? request.data() : Map.of()
        );

        try {
            IngestEventCommand.IngestResult result = ingestEventUseCase.ingest(command);
            log.info("Successfully ingested inbound message notificationId={}, replay={}",
                    result.notificationId(), result.isReplay());
        } catch (Exception ex) {
            log.error("Failed to ingest inbound event: {}", ex.getMessage(), ex);
            sendToDlq(rawMessage, "INGESTION_ERROR", ex.getMessage());
        }
    }

    private String computePayloadHash(IngestEventRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String canonical = objectMapper.writeValueAsString(request);
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException("Error computing payload SHA-256 hash", e);
        }
    }

    private void sendToDlq(Message rawMessage, String reason, String detail) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding("UTF-8");
        properties.setHeader("x-failure-reason", reason);
        if (detail != null) {
            properties.setHeader("x-exception-message", detail);
        }
        Message dlqMessage = new Message(rawMessage.getBody(), properties);
        rabbitTemplate.send(RabbitConfig.DLX_EXCHANGE, RabbitConfig.ROUTING_KEY_DEAD, dlqMessage);
    }
}
