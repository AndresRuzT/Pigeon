package io.github.andres.pigeon.application.port.out;

import io.github.andres.pigeon.domain.model.AuditRecord;

import java.util.List;
import java.util.UUID;

public interface AuditLogPort {
    void append(AuditRecord record);
    List<AuditRecord> findByNotificationId(UUID notificationId);
}
