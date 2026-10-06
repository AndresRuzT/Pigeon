package io.github.andres.pigeon.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.andres.pigeon.application.port.in.IngestEventCommand;
import io.github.andres.pigeon.application.port.in.IngestEventUseCase;
import io.github.andres.pigeon.application.port.in.ProcessNotificationUseCase;
import io.github.andres.pigeon.domain.enums.Priority;
import io.github.andres.pigeon.infrastructure.adapter.in.messaging.RabbitInboundEventListener;
import io.github.andres.pigeon.infrastructure.adapter.in.messaging.RabbitNotificationListener;
import io.github.andres.pigeon.infrastructure.config.RabbitConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.springframework.amqp.rabbit.core.RabbitOperations;

@ExtendWith(MockitoExtension.class)
class RabbitMessagingListenersTest {

    @Mock
    private ProcessNotificationUseCase processNotificationUseCase;

    @Mock
    private IngestEventUseCase ingestEventUseCase;

    @Mock
    private RabbitOperations rabbitTemplate;

    @Mock
    private io.github.andres.pigeon.application.port.out.NotificationRepository notificationRepository;

    @Mock
    private io.github.andres.pigeon.application.port.out.AuditLogPort auditLogPort;

    @Mock
    private io.github.andres.pigeon.application.port.out.ClockPort clockPort;

    @Mock
    private io.github.andres.pigeon.application.port.out.MetricsPort metricsPort;

    private ObjectMapper objectMapper;
    private RabbitNotificationListener notificationListener;
    private RabbitInboundEventListener inboundEventListener;
    private io.github.andres.pigeon.infrastructure.adapter.in.messaging.RabbitExpiredListener expiredListener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        notificationListener = new RabbitNotificationListener(processNotificationUseCase, rabbitTemplate, objectMapper);
        inboundEventListener = new RabbitInboundEventListener(ingestEventUseCase, rabbitTemplate, objectMapper);
        expiredListener = new io.github.andres.pigeon.infrastructure.adapter.in.messaging.RabbitExpiredListener(
                notificationRepository, auditLogPort, clockPort, objectMapper, metricsPort
        );
    }

    private Message createMessage(String payload) {
        return new Message(payload.getBytes(StandardCharsets.UTF_8), new MessageProperties());
    }

    @Test
    @DisplayName("Should process valid delivery envelope from queue")
    void shouldProcessNotificationEnvelope() {
        UUID id = UUID.randomUUID();
        String payload = """
                {"notificationId":"%s","eventType":"TRANSFER_COMPLETED","priority":"LOW","attempt":1}
                """.formatted(id);

        notificationListener.onMessage(createMessage(payload));

        verify(processNotificationUseCase).process(id);
    }

    @Test
    @DisplayName("Should route failed LOW notification to 30s retry queue on first failure")
    void shouldRouteToDelayedLadderOnFailure() {
        UUID id = UUID.randomUUID();
        String payload = """
                {"notificationId":"%s","eventType":"TRANSFER_COMPLETED","priority":"LOW","attempt":1}
                """.formatted(id);

        doThrow(new RuntimeException("Simulated delivery error"))
                .when(processNotificationUseCase).process(id);

        notificationListener.onMessage(createMessage(payload));

        verify(rabbitTemplate).convertAndSend(
                eq(RabbitConfig.RETRY_EXCHANGE),
                eq(RabbitConfig.ROUTING_KEY_RETRY_30S),
                contains("\"attempt\":2")
        );
    }

    @Test
    @DisplayName("Should route poison message with malformed JSON directly to DLQ")
    void shouldRoutePoisonMessageToDlq() {
        String malformedJson = "{not-json";

        notificationListener.onMessage(createMessage(malformedJson));

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(RabbitConfig.DLX_EXCHANGE), eq(RabbitConfig.ROUTING_KEY_DEAD), messageCaptor.capture());
        assertThat((String) messageCaptor.getValue().getMessageProperties().getHeader("x-failure-reason")).isEqualTo("POISON_MESSAGE");
    }

    @Test
    @DisplayName("Should ingest valid event from inbound queue")
    void shouldIngestValidEventFromInboundQueue() {
        String json = """
                {
                    "customerId": "cus_8F2A91",
                    "eventType": "TRANSFER_COMPLETED",
                    "occurredAt": "2026-10-01T12:00:00Z",
                    "data": {"amount": "100.00", "currency": "USD"}
                }
                """;
        MessageProperties properties = new MessageProperties();
        properties.setHeader("Idempotency-Key", "inbound-key-1");
        properties.setHeader("Client-Id", "core-banking");
        Message message = new Message(json.getBytes(StandardCharsets.UTF_8), properties);

        UUID generatedId = UUID.randomUUID();
        when(ingestEventUseCase.ingest(any(IngestEventCommand.class)))
                .thenReturn(new IngestEventCommand.IngestResult(generatedId, "PENDING", Priority.LOW, false));

        inboundEventListener.onInboundEvent(message);

        ArgumentCaptor<IngestEventCommand> captor = ArgumentCaptor.forClass(IngestEventCommand.class);
        verify(ingestEventUseCase).ingest(captor.capture());
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("inbound-key-1");
        assertThat(captor.getValue().clientId()).isEqualTo("core-banking");
    }

    @Test
    @DisplayName("Should detect sensitive card data in inbound message and route to DLQ")
    void shouldRejectSensitiveDataInInboundQueue() {
        // Valid Luhn card number 4532015112830366
        String json = """
                {
                    "customerId": "cus_8F2A91",
                    "eventType": "TRANSFER_COMPLETED",
                    "occurredAt": "2026-10-01T12:00:00Z",
                    "data": {"cardNumber": "4532015112830366"}
                }
                """;
        MessageProperties properties = new MessageProperties();
        properties.setHeader("Idempotency-Key", "inbound-key-pan");
        Message message = new Message(json.getBytes(StandardCharsets.UTF_8), properties);

        inboundEventListener.onInboundEvent(message);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(RabbitConfig.DLX_EXCHANGE), eq(RabbitConfig.ROUTING_KEY_DEAD), messageCaptor.capture());
        assertThat((String) messageCaptor.getValue().getMessageProperties().getHeader("x-failure-reason")).isEqualTo("SENSITIVE_DATA_DETECTED");
    }

    @Test
    @DisplayName("Should mark notification as EXPIRED when TTL expires in high priority queue")
    void shouldMarkNotificationAsExpiredWhenTtlExpires() {
        UUID id = UUID.randomUUID();
        String payload = """
                {"notificationId":"%s","eventType":"OTP_REQUESTED","priority":"HIGH"}
                """.formatted(id);

        io.github.andres.pigeon.domain.model.Notification notification = io.github.andres.pigeon.domain.model.Notification.createPending(
                "bank", io.github.andres.pigeon.domain.vo.IdempotencyKey.of("k"), "h",
                io.github.andres.pigeon.domain.vo.CustomerId.of("c"), io.github.andres.pigeon.domain.enums.EventType.OTP_REQUESTED,
                "en", java.util.Map.of(), java.time.Instant.now()
        );
        when(clockPort.now()).thenReturn(java.time.Instant.now());
        when(notificationRepository.findById(id)).thenReturn(java.util.Optional.of(notification));

        expiredListener.onExpiredMessage(createMessage(payload));

        assertThat(notification.getStatus()).isEqualTo(io.github.andres.pigeon.domain.enums.NotificationStatus.FAILED);
        assertThat(notification.getFailureReason()).isEqualTo(io.github.andres.pigeon.domain.enums.FailureReason.EXPIRED);
        verify(notificationRepository).save(notification);
        verify(auditLogPort).append(any());
    }
}
