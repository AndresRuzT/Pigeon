package io.github.andres.pigeon.application.port.out;

import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.enums.EventType;

import java.util.Map;

public interface TemplateEnginePort {

    record RenderedMessage(String subjectOrTitle, String body, String templateVersion) {}

    RenderedMessage render(EventType eventType, Channel channel, String locale, Map<String, Object> data);
}
