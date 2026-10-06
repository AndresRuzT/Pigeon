package io.github.andres.pigeon.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.andres.pigeon.application.port.out.AuditLogPort;
import io.github.andres.pigeon.application.port.out.NotificationRepository;
import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.domain.enums.NotificationStatus;
import io.github.andres.pigeon.domain.model.Notification;
import io.github.andres.pigeon.domain.vo.CustomerId;
import io.github.andres.pigeon.domain.vo.IdempotencyKey;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class WebhookReceiptIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("pigeon")
            .withUsername("pigeon_test")
            .withPassword("pigeon_test");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("pigeon.security.jwt.public-key-location", () -> "classpath:certs/app.pub");
        registry.add("pigeon.webhooks.secret", () -> "test_webhook_secret_key_1234567890");
        registry.add("management.health.mail.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private AuditLogPort auditLogPort;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${pigeon.webhooks.secret:test_webhook_secret_key_1234567890}")
    private String webhookSecret;

    private String computeHmac(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(keySpec);
        return HexFormat.of().formatHex(mac.doFinal(body));
    }

    @Test
    @DisplayName("Should accept valid signed delivery receipt and transition notification to DELIVERED")
    void shouldAcceptValidSignedReceipt() throws Exception {
        Instant now = Instant.now();
        Notification notification = Notification.createPending(
                "wh-client",
                IdempotencyKey.of("key-wh-" + UUID.randomUUID()),
                "hash-wh",
                CustomerId.of("cus_8F2A91"),
                EventType.TRANSFER_COMPLETED,
                "es",
                Map.of(),
                now
        );
        notification.markSent(Channel.PUSH, "provider_ref_push_1", 20L, "system", null, now);
        notificationRepository.save(notification);

        String jsonPayload = """
                {
                    "notificationId": "%s",
                    "providerRef": "provider_ref_push_1",
                    "status": "DELIVERED",
                    "reason": "Handset acknowledged"
                }
                """.formatted(notification.getId()).trim();

        byte[] payloadBytes = jsonPayload.getBytes(StandardCharsets.UTF_8);
        String signature = computeHmac(payloadBytes);

        mockMvc.perform(post("/api/v1/webhooks/push/receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", signature)
                        .content(payloadBytes))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notificationId", is(notification.getId().toString())))
                .andExpect(jsonPath("$.status", is("DELIVERED")))
                .andExpect(jsonPath("$.processed", is(true)));

        Notification updated = notificationRepository.findById(notification.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(NotificationStatus.DELIVERED);

        var auditTrail = auditLogPort.findByNotificationId(notification.getId());
        assertThat(auditTrail).anyMatch(a -> a.toStatus() == NotificationStatus.DELIVERED && "webhook".equals(a.actor()));
    }

    @Test
    @DisplayName("Should reject receipt when HMAC signature is invalid or tampered")
    void shouldRejectInvalidSignature() throws Exception {
        String jsonPayload = "{\"notificationId\":\"" + UUID.randomUUID() + "\",\"status\":\"DELIVERED\"}";

        mockMvc.perform(post("/api/v1/webhooks/sms/receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", "invalid_bad_signature_hex")
                        .content(jsonPayload.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title", is("Invalid Webhook Signature")));
    }

    @Test
    @DisplayName("Should reject receipt when X-Signature header is missing")
    void shouldRejectMissingSignatureHeader() throws Exception {
        String jsonPayload = "{\"notificationId\":\"" + UUID.randomUUID() + "\",\"status\":\"DELIVERED\"}";

        mockMvc.perform(post("/api/v1/webhooks/sms/receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPayload.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title", is("Invalid Webhook Signature")));
    }
}
