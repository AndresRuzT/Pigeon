package io.github.andres.pigeon.application.port.out;

import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.enums.DeliveryConfirmationPolicy;

public interface DeliveryPolicyPort {
    DeliveryConfirmationPolicy getConfirmationPolicy(Channel channel);
}
