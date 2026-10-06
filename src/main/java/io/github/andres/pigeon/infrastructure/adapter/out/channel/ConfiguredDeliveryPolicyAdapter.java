package io.github.andres.pigeon.infrastructure.adapter.out.channel;

import io.github.andres.pigeon.application.port.out.DeliveryPolicyPort;
import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.enums.DeliveryConfirmationPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ConfiguredDeliveryPolicyAdapter implements DeliveryPolicyPort {

    private final DeliveryConfirmationPolicy pushPolicy;
    private final DeliveryConfirmationPolicy smsPolicy;
    private final DeliveryConfirmationPolicy emailPolicy;

    public ConfiguredDeliveryPolicyAdapter(
            @Value("${pigeon.delivery.confirmation-policy.push:ON_ACCEPT}") String push,
            @Value("${pigeon.delivery.confirmation-policy.sms:ON_ACCEPT}") String sms,
            @Value("${pigeon.delivery.confirmation-policy.email:ON_ACCEPT}") String email
    ) {
        this.pushPolicy = parsePolicy(push);
        this.smsPolicy = parsePolicy(sms);
        this.emailPolicy = parsePolicy(email);
    }

    private static DeliveryConfirmationPolicy parsePolicy(String value) {
        if (value == null) {
            return DeliveryConfirmationPolicy.ON_ACCEPT;
        }
        try {
            return DeliveryConfirmationPolicy.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return DeliveryConfirmationPolicy.ON_ACCEPT;
        }
    }

    @Override
    public DeliveryConfirmationPolicy getConfirmationPolicy(Channel channel) {
        return switch (channel) {
            case PUSH -> pushPolicy;
            case SMS -> smsPolicy;
            case EMAIL -> emailPolicy;
        };
    }
}
