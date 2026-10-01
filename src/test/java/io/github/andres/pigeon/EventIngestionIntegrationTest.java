package io.github.andres.pigeon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.infrastructure.adapter.in.rest.dto.IngestEventRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Testcontainers
class EventIngestionIntegrationTest {

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
        registry.add("pigeon.outbox.poll-interval-ms", () -> "100");
        registry.add("pigeon.providers.sms.base-url", () -> "http://localhost:" + wireMockServer.port());
        registry.add("management.health.mail.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private JavaMailSender mailSender;

    private static String validJwtToken;

    @BeforeAll
    static void setupAll() throws Exception {
        wireMockServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMockServer.start();
        WireMock.configureFor("localhost", wireMockServer.port());

        ClassPathResource keyResource = new ClassPathResource("certs/app.key");
        String keyContent = new String(keyResource.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");

        byte[] decoded = Base64.getDecoder().decode(keyContent);
        KeyFactory keyFactory = KeyFactory.getInstance("RSA");
        RSAPrivateKey privateKey = (RSAPrivateKey) keyFactory.generatePrivate(new PKCS8EncodedKeySpec(decoded));

        JWTClaimsSet claimsSet = new JWTClaimsSet.Builder()
                .subject("test-banking-core")
                .claim("client_id", "test-banking-core")
                .claim("azp", "test-banking-core")
                .claim("scope", "notifications:write notifications:read")
                .issuer("pigeon-dev-issuer")
                .expirationTime(new Date(System.currentTimeMillis() + 3600000))
                .build();

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claimsSet);
        signedJWT.sign(new RSASSASigner(privateKey));
        validJwtToken = signedJWT.serialize();
    }

    @AfterAll
    static void tearDownAll() {
        if (wireMockServer != null) {
            wireMockServer.stop();
        }
    }

    @BeforeEach
    void resetWireMock() {
        wireMockServer.resetAll();
    }

    @Test
    @DisplayName("Should accept valid event, return 202 Accepted, and allow idempotent replay with 200 OK")
    void shouldIngestAndReplayEvent() throws Exception {
        stubFor(WireMock.post("/api/v1/sms")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"providerRef\":\"wm_sms_ok_1\",\"status\":\"ACCEPTED\"}")));

        String idempotencyKey = UUID.randomUUID().toString();

        IngestEventRequest request = new IngestEventRequest(
                EventType.TRANSFER_COMPLETED,
                "cus_8F2A91",
                Instant.now(),
                "es",
                Map.of(
                        "amount", "250000.00",
                        "currency", "COP",
                        "accountLast4", "4821",
                        "beneficiaryName", "Maria G."
                )
        );

        String responseBody = mockMvc.perform(post("/api/v1/events")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.notificationId", notNullValue()))
                .andExpect(jsonPath("$.status", is("PENDING")))
                .andExpect(jsonPath("$.priority", is("LOW")))
                .andReturn().getResponse().getContentAsString();

        String notificationId = objectMapper.readTree(responseBody).path("notificationId").asText();

        // Replay with exact same idempotency key and payload -> must return 200 OK with Idempotency-Replayed header
        mockMvc.perform(post("/api/v1/events")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotency-Replayed", "true"))
                .andExpect(jsonPath("$.notificationId", is(notificationId)));

        // Query notification endpoint
        mockMvc.perform(get("/api/v1/notifications/" + notificationId)
                        .header("Authorization", "Bearer " + validJwtToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(notificationId)))
                .andExpect(jsonPath("$.customerId", is("cus_8F2A91")));
    }

    @Test
    @DisplayName("Concurrency test: 50 parallel requests with identical idempotency key produce exactly one notification")
    void concurrencyTest50IdenticalRequests() throws Exception {
        String sharedKey = "concurrency-" + UUID.randomUUID();
        IngestEventRequest request = new IngestEventRequest(
                EventType.TRANSFER_COMPLETED,
                "cus_8F2A91",
                Instant.now(),
                "es",
                Map.of("amount", "500.00", "currency", "USD", "accountLast4", "4821")
        );
        String requestJson = objectMapper.writeValueAsString(request);

        int concurrency = 50;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(concurrency);
        List<String> notificationIds = Collections.synchronizedList(new ArrayList<>());
        List<Integer> statusCodes = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    var mvcResult = mockMvc.perform(post("/api/v1/events")
                                    .header("Authorization", "Bearer " + validJwtToken)
                                    .header("Idempotency-Key", sharedKey)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(requestJson))
                            .andReturn();

                    int status = mvcResult.getResponse().getStatus();
                    statusCodes.add(status);
                    String body = mvcResult.getResponse().getContentAsString();
                    String id = objectMapper.readTree(body).path("notificationId").asText();
                    if (!id.isBlank()) {
                        notificationIds.add(id);
                    }
                } catch (Exception e) {
                    // exception caught
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(notificationIds).hasSize(concurrency);
        String distinctId = notificationIds.get(0);
        assertThat(notificationIds).allMatch(id -> id.equals(distinctId));
        assertThat(statusCodes).allMatch(code -> code == 202 || code == 200);
    }

    @Test
    @DisplayName("Resilience fault-injection test: SMS 500 error causes fallback to Email")
    void shouldFallbackToEmailWhenSmsFails() throws Exception {
        stubFor(WireMock.post("/api/v1/sms")
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"SMS_GATEWAY_DOWN\"}")));

        String idempotencyKey = UUID.randomUUID().toString();
        IngestEventRequest request = new IngestEventRequest(
                EventType.TRANSFER_COMPLETED,
                "cus_8F2A91",
                Instant.now(),
                "es",
                Map.of("amount", "100.00", "currency", "USD", "accountLast4", "4821")
        );

        String responseBody = mockMvc.perform(post("/api/v1/events")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();

        String notificationId = objectMapper.readTree(responseBody).path("notificationId").asText();

        // Await delivery orchestrator fallback to EMAIL
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> {
                    mockMvc.perform(get("/api/v1/notifications/" + notificationId)
                                    .header("Authorization", "Bearer " + validJwtToken))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.status", is("DELIVERED")))
                            .andExpect(jsonPath("$.attempts.length()", is(2)));
                });

        verify(postRequestedFor(urlEqualTo("/api/v1/sms")));
    }

    @Test
    @DisplayName("Should reject event containing full credit card number with 400 Problem Details")
    void shouldRejectFullCreditCard() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();

        IngestEventRequest request = new IngestEventRequest(
                EventType.TRANSFER_COMPLETED,
                "cus_8F2A91",
                Instant.now(),
                "es",
                Map.of("cardNumber", "4532015112830366")
        );

        mockMvc.perform(post("/api/v1/events")
                        .header("Authorization", "Bearer " + validJwtToken)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title", is("Sensitive Data Detected")))
                .andExpect(jsonPath("$.type", is("https://pigeon.bank.internal/errors/sensitive-data-rejected")));
    }

    @Test
    @DisplayName("Should return 401 Unauthorized when request lacks Bearer token")
    void shouldRejectUnauthenticatedRequest() throws Exception {
        IngestEventRequest request = new IngestEventRequest(
                EventType.TRANSFER_COMPLETED,
                "cus_8F2A91",
                Instant.now(),
                "es",
                Map.of("amount", "100.00")
        );

        mockMvc.perform(post("/api/v1/events")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }
}
