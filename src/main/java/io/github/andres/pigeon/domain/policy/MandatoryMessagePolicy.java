package io.github.andres.pigeon.domain.policy;

import io.github.andres.pigeon.domain.enums.EventType;

public final class MandatoryMessagePolicy {
    private MandatoryMessagePolicy() {}

    public static boolean isMandatory(EventType eventType) {
        return switch (eventType) {
            case OTP_REQUESTED, FRAUD_SUSPECTED -> true;
            case TRANSFER_COMPLETED, PURCHASE_DECLINED, PAYMENT_REMINDER -> false;
        };
    }
}
