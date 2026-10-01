package io.github.andres.pigeon.application.port.out;

import io.github.andres.pigeon.domain.model.Notification;

public interface EmailSenderPort {
    EmailSendResult sendEmail(String recipientEmail, Notification notification);

    record EmailSendResult(
            boolean success,
            String providerRef,
            String errorCode,
            long latencyMs
    ) {
        public static EmailSendResult ok(String providerRef, long latencyMs) {
            return new EmailSendResult(true, providerRef, null, latencyMs);
        }

        public static EmailSendResult error(String errorCode, long latencyMs) {
            return new EmailSendResult(false, null, errorCode, latencyMs);
        }
    }
}
