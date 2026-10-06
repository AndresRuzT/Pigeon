package io.github.andres.pigeon.infrastructure.adapter.out.messaging;

import io.github.andres.pigeon.application.port.out.MessagePublisher;
import io.github.andres.pigeon.infrastructure.config.RabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class RabbitMessagePublisher implements MessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitMessagePublisher.class);
    private static final long CONFIRM_TIMEOUT_SECONDS = 5;

    private final RabbitTemplate rabbitTemplate;

    public RabbitMessagePublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publish(String routingKey, String payload) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding("UTF-8");
        Message message = new Message(payload.getBytes(StandardCharsets.UTF_8), properties);

        CorrelationData correlationData = new CorrelationData(UUID.randomUUID().toString());
        rabbitTemplate.send(RabbitConfig.EVENTS_EXCHANGE, routingKey, message, correlationData);

        try {
            CorrelationData.Confirm confirm = correlationData.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (confirm == null || !confirm.isAck()) {
                String reason = confirm != null ? confirm.getReason() : "Broker confirm timed out";
                throw new IllegalStateException("RabbitMQ broker nack or timeout for routingKey " + routingKey + ": " + reason);
            }
            if (correlationData.getReturned() != null) {
                throw new IllegalStateException("RabbitMQ unroutable message returned for routingKey: " + routingKey);
            }
            log.debug("Broker confirmed message publish to exchange {} with routingKey {}", RabbitConfig.EVENTS_EXCHANGE, routingKey);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Thread interrupted while awaiting publisher confirm", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Failed to receive RabbitMQ publisher confirm within " + CONFIRM_TIMEOUT_SECONDS + "s", e);
        }
    }
}
