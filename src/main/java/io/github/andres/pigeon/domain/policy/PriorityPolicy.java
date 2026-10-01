package io.github.andres.pigeon.domain.policy;

import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.domain.enums.Priority;

public final class PriorityPolicy {
    private PriorityPolicy() {}

    public static Priority determinePriority(EventType eventType) {
        return switch (eventType) {
            case OTP_REQUESTED, FRAUD_SUSPECTED -> Priority.HIGH;
            case TRANSFER_COMPLETED, PURCHASE_DECLINED, PAYMENT_REMINDER -> Priority.LOW;
        };
    }
}
