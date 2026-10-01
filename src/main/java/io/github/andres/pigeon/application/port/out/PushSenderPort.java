package io.github.andres.pigeon.application.port.out;

import io.github.andres.pigeon.domain.model.Notification;

public interface PushSenderPort {

    record PushSendResult(boolean success, String providerRef, String errorCode, long latencyMs) {
        public static PushSendResult success(String providerRef, long latencyMs) {
            return new PushSendResult(true, providerRef, null, latencyMs);
        }

        public static PushSendResult failure(String errorCode, long latencyMs) {
            return new PushSendResult(false, null, errorCode, latencyMs);
        }
    }

    PushSendResult sendPush(String pushToken, Notification notification, String title, String body);
}
