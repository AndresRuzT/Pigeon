package io.github.andres.pigeon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.infrastructure.adapter.in.rest.dto.IngestEventRequest;
import org.junit.jupiter.api.BeforeAll;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

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

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("pigeon")
            .withUsername("pigeon_test")
            .withPassword("pigeon_test");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("pigeon.security.jwt.public-key-location", () -> "classpath:certs/app.pub");
        registry.add("pigeon.outbox.poll-interval-ms", () -> "100");
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
    static void setupJwt() throws Exception {
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

    @Test
    @DisplayName("Should accept valid event, return 202 Accepted, and allow idempotent replay with 200 OK")
    void shouldIngestAndReplayEvent() throws Exception {
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
    @DisplayName("Should reject event containing full credit card number with 400 Problem Details")
    void shouldRejectFullCreditCard() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();

        // Using a Luhn-valid test card number
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
