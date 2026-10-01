package io.github.andres.pigeon.infrastructure.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    public static final String EVENTS_EXCHANGE = "pigeon.events";
    public static final String RETRY_EXCHANGE = "pigeon.retry";
    public static final String DLX_EXCHANGE = "pigeon.dlx";

    public static final String QUEUE_EVENTS_LOW = "pigeon.events.low";
    public static final String QUEUE_EVENTS_HIGH = "pigeon.events.high";
    public static final String QUEUE_EXPIRED = "pigeon.expired";
    public static final String QUEUE_DLQ = "pigeon.dlq";

    public static final String ROUTING_KEY_LOW = "low";
    public static final String ROUTING_KEY_HIGH = "high";
    public static final String ROUTING_KEY_EXPIRED = "expired";
    public static final String ROUTING_KEY_DEAD = "dead";

    @Value("${pigeon.messaging.high-priority-ttl-ms:60000}")
    private int highPriorityTtlMs;

    @Bean
    public DirectExchange eventsExchange() {
        return new DirectExchange(EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange retryExchange() {
        return new DirectExchange(RETRY_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue eventsLowQueue() {
        return QueueBuilder.durable(QUEUE_EVENTS_LOW)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(ROUTING_KEY_DEAD)
                .build();
    }

    @Bean
    public Queue eventsHighQueue() {
        return QueueBuilder.durable(QUEUE_EVENTS_HIGH)
                .ttl(highPriorityTtlMs)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(ROUTING_KEY_EXPIRED)
                .build();
    }

    @Bean
    public Queue expiredQueue() {
        return QueueBuilder.durable(QUEUE_EXPIRED).build();
    }

    @Bean
    public Queue dlqQueue() {
        return QueueBuilder.durable(QUEUE_DLQ).build();
    }

    @Bean
    public Binding bindingEventsLow(Queue eventsLowQueue, DirectExchange eventsExchange) {
        return BindingBuilder.bind(eventsLowQueue).to(eventsExchange).with(ROUTING_KEY_LOW);
    }

    @Bean
    public Binding bindingEventsHigh(Queue eventsHighQueue, DirectExchange eventsExchange) {
        return BindingBuilder.bind(eventsHighQueue).to(eventsExchange).with(ROUTING_KEY_HIGH);
    }

    @Bean
    public Binding bindingExpired(Queue expiredQueue, DirectExchange dlxExchange) {
        return BindingBuilder.bind(expiredQueue).to(dlxExchange).with(ROUTING_KEY_EXPIRED);
    }

    @Bean
    public Binding bindingDlq(Queue dlqQueue, DirectExchange dlxExchange) {
        return BindingBuilder.bind(dlqQueue).to(dlxExchange).with(ROUTING_KEY_DEAD);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        template.setMandatory(true);
        return template;
    }
}
