package io.github.andres.pigeon.infrastructure.adapter.out.template;

import io.github.andres.pigeon.application.port.out.TemplateEnginePort;
import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.enums.EventType;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Thymeleaf implementation of {@link TemplateEnginePort} ensuring:
 * - Versioned multilingual templates (en, es) with automatic fallback to en
 * - Safe variable allowlisting per EventType (SSTI prevention)
 * - Safe HTML escaping via th:text for email channels
 * - Text mode for SMS and Push notifications
 */
@Component
public class ThymeleafTemplateAdapter implements TemplateEnginePort {

    public static final String CURRENT_VERSION = "v1.0.0";

    private static final Map<EventType, Set<String>> SAFE_VARIABLES = Map.of(
            EventType.OTP_REQUESTED, Set.of("otpCode", "expiresInSeconds"),
            EventType.FRAUD_SUSPECTED, Set.of("amount", "currency", "cardLast4", "merchantName"),
            EventType.TRANSFER_COMPLETED, Set.of("amount", "currency", "accountLast4", "beneficiaryName"),
            EventType.PURCHASE_DECLINED, Set.of("amount", "currency", "cardLast4", "merchantName", "reasonCode"),
            EventType.PAYMENT_REMINDER, Set.of("amount", "currency", "dueDate", "accountLast4")
    );

    private static final Map<EventType, Map<String, String>> EMAIL_SUBJECTS = Map.of(
            EventType.OTP_REQUESTED, Map.of("en", "Security Verification Code", "es", "Código de Seguridad"),
            EventType.FRAUD_SUSPECTED, Map.of("en", "Urgent: Fraud Alert", "es", "Alerta Urgente de Fraude"),
            EventType.TRANSFER_COMPLETED, Map.of("en", "Transfer Confirmation", "es", "Confirmación de Transferencia"),
            EventType.PURCHASE_DECLINED, Map.of("en", "Purchase Declined Notice", "es", "Aviso de Compra Rechazada"),
            EventType.PAYMENT_REMINDER, Map.of("en", "Upcoming Payment Reminder", "es", "Recordatorio de Pago")
    );

    private final org.thymeleaf.ITemplateEngine templateEngine;

    public ThymeleafTemplateAdapter() {
        org.thymeleaf.spring6.SpringTemplateEngine engine = new org.thymeleaf.spring6.SpringTemplateEngine();
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setCacheable(true);
        resolver.setCharacterEncoding("UTF-8");
        engine.addTemplateResolver(resolver);
        this.templateEngine = engine;
    }

    @Override
    public RenderedMessage render(EventType eventType, Channel channel, String locale, Map<String, Object> data) {
        String effectiveLocale = (locale != null && locale.equalsIgnoreCase("es")) ? "es" : "en";
        String templateName = eventType.name().toLowerCase() + "_" + effectiveLocale;

        Map<String, Object> sanitized = sanitizeVariables(eventType, data);
        Context context = new Context(Locale.forLanguageTag(effectiveLocale), sanitized);

        String templatePath;
        String subjectOrTitle = "Pigeon Notification";

        switch (channel) {
            case EMAIL -> {
                templatePath = "email/" + templateName + ".html";
                subjectOrTitle = EMAIL_SUBJECTS.getOrDefault(eventType, Map.of())
                        .getOrDefault(effectiveLocale, "Pigeon Notification");
            }
            case SMS -> {
                templatePath = "sms/" + templateName + ".txt";
                subjectOrTitle = "SMS Alert";
            }
            case PUSH -> {
                templatePath = "push/" + templateName + ".txt";
            }
            default -> throw new IllegalArgumentException("Unsupported channel: " + channel);
        }

        String rendered = templateEngine.process(templatePath, context);

        if (channel == Channel.PUSH) {
            String[] parts = rendered.split("\\R", 2);
            subjectOrTitle = parts[0].trim();
            rendered = parts.length > 1 ? parts[1].trim() : subjectOrTitle;
        } else if (channel == Channel.SMS) {
            rendered = rendered.trim();
        }

        return new RenderedMessage(subjectOrTitle, rendered, CURRENT_VERSION);
    }

    private Map<String, Object> sanitizeVariables(EventType eventType, Map<String, Object> data) {
        if (data == null) {
            return Map.of();
        }
        Set<String> allowed = SAFE_VARIABLES.getOrDefault(eventType, Set.of());
        Map<String, Object> filtered = new HashMap<>();
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            if (allowed.contains(entry.getKey())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }
}
