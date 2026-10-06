package io.github.andres.pigeon.infrastructure;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.andres.pigeon.application.port.out.NotificationRepository;
import io.github.andres.pigeon.application.port.out.OutboxRepository;
import io.github.andres.pigeon.application.service.OutboxRelayService;
import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.domain.enums.NotificationStatus;
import io.github.andres.pigeon.domain.model.Notification;
import io.github.andres.pigeon.domain.vo.CustomerId;
import io.github.andres.pigeon.domain.vo.IdempotencyKey;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
class OutboxCrashRecoveryIntegrationTest {

    private static WireMockServer wireMockServer;

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

    @BeforeAll
    static void startWireMock() {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();

        wireMockServer.stubFor(post(urlEqualTo("/api/v1/push"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"SUCCESS\",\"providerRef\":\"wm_push_recovered\"}")));
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

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
        registry.add("pigeon.outbox.poll-interval-ms", () -> "100000"); // manual trigger for determinism
        registry.add("pigeon.providers.push.base-url", () -> "http://localhost:" + wireMockServer.port());
        registry.add("pigeon.providers.sms.base-url", () -> "http://localhost:" + wireMockServer.port());
        registry.add("management.health.mail.enabled", () -> "false");
    }

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private OutboxRelayService outboxRelayService;

    @Test
    @DisplayName("Crash-Recovery: should relay unpublished outbox message after simulated crash and deliver notification")
    void shouldRelayOutboxMessageAfterCrash() {
        Instant now = Instant.now();
        UUID notificationId = UUID.randomUUID();

        // 1. Simulate uncommitted-to-broker state: Notification is PENDING in DB, Outbox row is unpublished
        Notification notification = Notification.createPending(
                "crash-client",
                IdempotencyKey.of("key-crash-" + UUID.randomUUID()),
                "hash-crash",
                CustomerId.of("cus_8F2A91"),
                EventType.TRANSFER_COMPLETED,
                "es",
                Map.of("amount", "50000.00", "currency", "COP", "accountLast4", "4821", "beneficiaryName", "Maria G."),
                now
        );
        notificationRepository.save(notification);

        String envelope = """
                {"notificationId":"%s","eventType":"TRANSFER_COMPLETED","priority":"LOW","attempt":1,"schemaVersion":1}
                """.formatted(notification.getId()).trim();

        UUID outboxId = UUID.randomUUID();
        OutboxRepository.OutboxMessage outboxMessage = new OutboxRepository.OutboxMessage(
                outboxId,
                notification.getId(),
                "TRANSFER_COMPLETED",
                "low",
                envelope,
                now,
                null, // unpublished!
                0
        );
        outboxRepository.save(outboxMessage);

        assertThat(outboxRepository.countUnpublished()).isGreaterThanOrEqualTo(1L);

        // 2. Trigger the relay (simulates application restart recovery)
        outboxRelayService.relayMessages();

        // 3. Await delivery to completion via RabbitMQ consumer
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    Optional<Notification> deliveredOpt = notificationRepository.findById(notification.getId());
                    assertThat(deliveredOpt).isPresent();
                    assertThat(deliveredOpt.get().getStatus()).isEqualTo(NotificationStatus.DELIVERED);
                });

        // 4. Assert outbox event was marked as published
        var pendingMessages = outboxRepository.lockNextBatch(10);
        boolean stillPending = pendingMessages.stream().anyMatch(m -> m.id().equals(outboxId));
        assertThat(stillPending).isFalse();
    }
}
