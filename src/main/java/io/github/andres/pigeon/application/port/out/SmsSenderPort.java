package io.github.andres.pigeon.application.port.out;

import io.github.andres.pigeon.domain.model.Notification;

public interface SmsSenderPort {

    record SmsSendResult(boolean success, String providerRef, String errorCode, long latencyMs) {
        public static SmsSendResult success(String providerRef, long latencyMs) {
            return new SmsSendResult(true, providerRef, null, latencyMs);
        }

        public static SmsSendResult failure(String errorCode, long latencyMs) {
            return new SmsSendResult(false, null, errorCode, latencyMs);
        }
    }

    SmsSendResult sendSms(String phoneNumber, Notification notification);
}
