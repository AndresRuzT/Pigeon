package io.github.andres.pigeon.infrastructure;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.github.andres.pigeon.application.port.out.PushSenderPort;
import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.domain.model.Notification;
import io.github.andres.pigeon.domain.vo.CustomerId;
import io.github.andres.pigeon.domain.vo.IdempotencyKey;
import io.github.andres.pigeon.infrastructure.adapter.out.channel.PushChannelSender;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;

class PushChannelSenderTest {

    private static WireMockServer wireMock;

    private CircuitBreaker circuitBreaker;
    private Retry retry;
    private PushChannelSender sender;
    private Notification notification;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .slidingWindowSize(5)
                .failureRateThreshold(50.0f)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .build();
        circuitBreaker = CircuitBreaker.of("push-test", cbConfig);

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(2)
                .waitDuration(Duration.ofMillis(10))
                .ignoreExceptions(HttpClientErrorException.class)
                .build();
        retry = Retry.of("push-test", retryConfig);

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(2000);
        requestFactory.setReadTimeout(2000);

        RestClient restClient = RestClient.builder()
                .baseUrl("http://127.0.0.1:" + wireMock.port())
                .requestFactory(requestFactory)
                .build();

        sender = new PushChannelSender(restClient, circuitBreaker, retry);

        notification = Notification.createPending(
                "client-1",
                IdempotencyKey.of("key-push"),
                "hash-push",
                CustomerId.of("cus_8F2A91"),
                EventType.TRANSFER_COMPLETED,
                "es",
                Map.of(),
                Instant.now()
        );
    }

    @Test
    @DisplayName("Should return success when Push provider returns 200")
    void shouldReturnSuccessWhenProviderAccepts() {
        wireMock.stubFor(post("/api/v1/push")
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"providerRef\":\"wm_push_999\",\"status\":\"ACCEPTED\"}")));

        PushSenderPort.PushSendResult result = sender.sendPush("push_tok_123", notification, "Title", "Body");

        assertThat(result.success()).isTrue();
        assertThat(result.providerRef()).isEqualTo("wm_push_999");
        assertThat(result.errorCode()).isNull();
    }

    @Test
    @DisplayName("Should return INVALID_DESTINATION without retry when provider responds 400")
    void shouldReturnInvalidDestinationOn4xx() {
        wireMock.stubFor(post("/api/v1/push")
                .willReturn(aResponse()
                        .withStatus(400)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"INVALID_PUSH_TOKEN\"}")));

        PushSenderPort.PushSendResult result = sender.sendPush("bad_token", notification, "Title", "Body");

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("INVALID_DESTINATION");
    }

    @Test
    @DisplayName("Should return PROVIDER_UNAVAILABLE when server returns 500 error after retries")
    void shouldReturnProviderUnavailableOn5xx() {
        wireMock.stubFor(post("/api/v1/push")
                .willReturn(aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":\"INTERNAL_ERROR\"}")));

        PushSenderPort.PushSendResult result = sender.sendPush("push_tok_123", notification, "Title", "Body");

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("PROVIDER_UNAVAILABLE");
    }

    @Test
    @DisplayName("Should return CIRCUIT_OPEN when circuit breaker is forced open")
    void shouldReturnCircuitOpenWhenBreakerIsOpen() {
        circuitBreaker.transitionToOpenState();

        PushSenderPort.PushSendResult result = sender.sendPush("push_tok_123", notification, "Title", "Body");

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("CIRCUIT_OPEN");
    }
}
