package io.github.andres.pigeon.infrastructure.config;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MaskingPatternLayout extends PatternLayout {

    // Regex to match sequences of 12 or more consecutive digits (potential PANs or account numbers)
    private static final Pattern CARD_PATTERN = Pattern.compile("\\b(\\d{6})\\d{4,9}(\\d{4})\\b");

    @Override
    public String doLayout(ILoggingEvent event) {
        String message = super.doLayout(event);
        if (message == null || message.isEmpty()) {
            return message;
        }

        Matcher matcher = CARD_PATTERN.matcher(message);
        if (matcher.find()) {
            return matcher.replaceAll("$1******$2");
        }
        return message;
    }
}
