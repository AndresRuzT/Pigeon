package io.github.andres.pigeon.application.port.in;

import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.enums.NotificationStatus;

import java.time.Instant;
import java.util.UUID;

public interface ProcessWebhookReceiptUseCase {

    ReceiptResult processReceipt(ReceiptCommand command);

    record ReceiptCommand(
            Channel channel,
            UUID notificationId,
            String providerRef,
            NotificationStatus status,
            String errorCode,
            String reason,
            Instant occurredAt
    ) {}

    record ReceiptResult(
            UUID notificationId,
            NotificationStatus status,
            boolean processed
    ) {}
}
