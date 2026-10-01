package io.github.andres.pigeon.application.port.in;

import io.github.andres.pigeon.domain.model.AuditRecord;
import io.github.andres.pigeon.domain.model.Notification;

import java.util.List;
import java.util.UUID;

public interface QueryNotificationUseCase {
    Notification getNotification(UUID id);
    List<AuditRecord> getAuditTrail(UUID notificationId);
}
