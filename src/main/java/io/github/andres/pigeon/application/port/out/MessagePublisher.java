package io.github.andres.pigeon.application.port.out;

public interface MessagePublisher {
    void publish(String routingKey, String payload);
}
