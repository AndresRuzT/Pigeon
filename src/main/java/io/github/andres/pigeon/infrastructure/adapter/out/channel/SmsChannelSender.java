package io.github.andres.pigeon.infrastructure.adapter.out.channel;

import io.github.andres.pigeon.application.port.out.SmsSenderPort;
import io.github.andres.pigeon.domain.model.Notification;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.UUID;
import java.util.function.Supplier;

@Component
public class SmsChannelSender implements SmsSenderPort {

    private static final Logger log = LoggerFactory.getLogger(SmsChannelSender.class);

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public record SmsRequest(String to, String message, String notificationId) {}
    public record SmsResponse(String providerRef, String status) {}

    @org.springframework.beans.factory.annotation.Autowired
    public SmsChannelSender(
            @Value("${pigeon.providers.sms.base-url:http://localhost:8089}") String baseUrl,
            CircuitBreakerRegistry circuitBreakerRegistry,
            RetryRegistry retryRegistry
    ) {
        org.springframework.http.client.SimpleClientHttpRequestFactory requestFactory =
                new org.springframework.http.client.SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(2000);
        requestFactory.setReadTimeout(2000);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker("sms");
        this.retry = retryRegistry.retry("sms");
    }

    // Constructor for testing / custom RestClient injection
    public SmsChannelSender(
            RestClient restClient,
            CircuitBreaker circuitBreaker,
            Retry retry
    ) {
        this.restClient = restClient;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
    }

    @Override
    public SmsSendResult sendSms(String phoneNumber, Notification notification) {
        return sendSms(phoneNumber, notification, buildSmsContent(notification));
    }

    @Override
    public SmsSendResult sendSms(String phoneNumber, Notification notification, String renderedMessage) {
        long startTime = System.currentTimeMillis();
        String messageText = (renderedMessage != null && !renderedMessage.isBlank())
                ? renderedMessage
                : buildSmsContent(notification);

        Supplier<SmsResponse> sendSupplier = () -> restClient.post()
                .uri("/api/v1/sms")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new SmsRequest(phoneNumber, messageText, notification.getId().toString()))
                .retrieve()
                .body(SmsResponse.class);

        Supplier<SmsResponse> retryDecorated = Retry.decorateSupplier(retry, sendSupplier);
        Supplier<SmsResponse> decoratedSupplier = CircuitBreaker.decorateSupplier(circuitBreaker, retryDecorated);

        try {
            SmsResponse response = decoratedSupplier.get();
            long latency = System.currentTimeMillis() - startTime;
            String providerRef = response != null && response.providerRef() != null
                    ? response.providerRef()
                    : "wm_sms_" + UUID.randomUUID().toString().substring(0, 8);
            log.info("SMS delivered successfully to destination {} (notificationId={})", phoneNumber, notification.getId());
            return SmsSendResult.success(providerRef, latency);
        } catch (CallNotPermittedException ex) {
            long latency = System.currentTimeMillis() - startTime;
            log.warn("SMS Circuit Breaker [sms] is OPEN. Fast-failing notification {}: {}", notification.getId(), ex.getMessage());
            return SmsSendResult.failure("CIRCUIT_OPEN", latency);
        } catch (HttpClientErrorException ex) {
            long latency = System.currentTimeMillis() - startTime;
            log.error("SMS provider rejected destination {} with client error {}: {}", phoneNumber, ex.getStatusCode(), ex.getMessage());
            return SmsSendResult.failure("INVALID_DESTINATION", latency);
        } catch (Exception ex) {
            long latency = System.currentTimeMillis() - startTime;
            log.error("SMS delivery failed after retries for notification {}: {}", notification.getId(), ex.getMessage());
            return SmsSendResult.failure("PROVIDER_UNAVAILABLE", latency);
        }
    }

    private String buildSmsContent(Notification notification) {
        return "Pigeon Security Alert: " + notification.getEventType().name() + " notification.";
    }
}
