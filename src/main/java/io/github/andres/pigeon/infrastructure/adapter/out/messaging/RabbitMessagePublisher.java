package io.github.andres.pigeon.infrastructure.adapter.out.messaging;

import io.github.andres.pigeon.application.port.out.MessagePublisher;
import io.github.andres.pigeon.infrastructure.config.RabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class RabbitMessagePublisher implements MessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitMessagePublisher.class);

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

        rabbitTemplate.send(RabbitConfig.EVENTS_EXCHANGE, routingKey, message);
        log.debug("Published message to exchange {} with routingKey {}", RabbitConfig.EVENTS_EXCHANGE, routingKey);
    }
}
