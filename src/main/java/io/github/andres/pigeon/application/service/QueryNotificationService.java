package io.github.andres.pigeon.application.service;

import io.github.andres.pigeon.application.port.in.QueryNotificationUseCase;
import io.github.andres.pigeon.application.port.out.AuditLogPort;
import io.github.andres.pigeon.application.port.out.NotificationRepository;
import io.github.andres.pigeon.domain.exception.NotificationNotFoundException;
import io.github.andres.pigeon.domain.model.AuditRecord;
import io.github.andres.pigeon.domain.model.Notification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class QueryNotificationService implements QueryNotificationUseCase {

    private final NotificationRepository notificationRepository;
    private final AuditLogPort auditLogPort;

    public QueryNotificationService(NotificationRepository notificationRepository, AuditLogPort auditLogPort) {
        this.notificationRepository = notificationRepository;
        this.auditLogPort = auditLogPort;
    }

    @Override
    public Notification getNotification(UUID id) {
        return notificationRepository.findById(id)
                .orElseThrow(() -> new NotificationNotFoundException(id));
    }

    @Override
    public List<AuditRecord> getAuditTrail(UUID notificationId) {
        return auditLogPort.findByNotificationId(notificationId);
    }
}
