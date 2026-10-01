package io.github.andres.pigeon.infrastructure.adapter.out.channel;

import io.github.andres.pigeon.application.port.out.PushSenderPort;
import io.github.andres.pigeon.domain.model.Notification;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.UUID;
import java.util.function.Supplier;

@Component
public class PushChannelSender implements PushSenderPort {

    private static final Logger log = LoggerFactory.getLogger(PushChannelSender.class);

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public record PushRequest(String pushToken, String title, String body, String notificationId) {}
    public record PushResponse(String providerRef, String status) {}

    @Autowired
    public PushChannelSender(
            @Value("${pigeon.providers.push.base-url:http://localhost:8089}") String baseUrl,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry
    ) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(2000);
        requestFactory.setReadTimeout(2000);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("push");
        this.retry = retryRegistry.retry("push");
    }

    public PushChannelSender(
            RestClient restClient,
            CircuitBreaker circuitBreaker,
            Retry retry
    ) {
        this.restClient = restClient;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
    }

    @Override
    public PushSendResult sendPush(String pushToken, Notification notification, String title, String body) {
        long startTime = System.currentTimeMillis();

        Supplier<PushResponse> sendSupplier = () -> restClient.post()
                .uri("/api/v1/push")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new PushRequest(pushToken, title, body, notification.getId().toString()))
                .retrieve()
                .body(PushResponse.class);

        Supplier<PushResponse> retryDecorated = Retry.decorateSupplier(retry, sendSupplier);
        Supplier<PushResponse> decoratedSupplier = CircuitBreaker.decorateSupplier(circuitBreaker, retryDecorated);

        try {
            PushResponse response = decoratedSupplier.get();
            long latency = System.currentTimeMillis() - startTime;
            String providerRef = response != null && response.providerRef() != null
                    ? response.providerRef()
                    : "wm_push_" + UUID.randomUUID().toString().substring(0, 8);
            log.info("Push notification delivered successfully to destination token {} (notificationId={})",
                    pushToken, notification.getId());
            return PushSendResult.success(providerRef, latency);
        } catch (CallNotPermittedException ex) {
            long latency = System.currentTimeMillis() - startTime;
            log.warn("Push Circuit Breaker [push] is OPEN. Fast-failing notification {}: {}", notification.getId(), ex.getMessage());
            return PushSendResult.failure("CIRCUIT_OPEN", latency);
        } catch (HttpClientErrorException ex) {
            long latency = System.currentTimeMillis() - startTime;
            log.error("Push provider rejected token {} with client error {}: {}", pushToken, ex.getStatusCode(), ex.getMessage());
            return PushSendResult.failure("INVALID_DESTINATION", latency);
        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - startTime;
            log.error("Push delivery failed after retries for notification {}: {}", notification.getId(), ex.getMessage());
            return PushSendResult.failure("PROVIDER_UNAVAILABLE", latency);
        }
    }
}
