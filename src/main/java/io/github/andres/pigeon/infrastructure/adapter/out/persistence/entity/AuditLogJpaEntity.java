package io.github.andres.pigeon.infrastructure.adapter.out.persistence.entity;

import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.enums.NotificationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
public class AuditLogJpaEntity {

    @Id
    private UUID id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "actor", nullable = false, length = 64)
    private String actor;

    @Column(name = "action", nullable = false, length = 64)
    private String action;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status", length = 32)
    private NotificationStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false, length = 32)
    private NotificationStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 16)
    private Channel channel;

    @Column(name = "template_version", length = 16)
    private String templateVersion;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "prev_hash", length = 128)
    private String prevHash;

    public AuditLogJpaEntity() {}

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
    public UUID getNotificationId() { return notificationId; }
    public void setNotificationId(UUID notificationId) { this.notificationId = notificationId; }
    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public NotificationStatus getFromStatus() { return fromStatus; }
    public void setFromStatus(NotificationStatus fromStatus) { this.fromStatus = fromStatus; }
    public NotificationStatus getToStatus() { return toStatus; }
    public void setToStatus(NotificationStatus toStatus) { this.toStatus = toStatus; }
    public Channel getChannel() { return channel; }
    public void setChannel(Channel channel) { this.channel = channel; }
    public String getTemplateVersion() { return templateVersion; }
    public void setTemplateVersion(String templateVersion) { this.templateVersion = templateVersion; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    public String getPrevHash() { return prevHash; }
    public void setPrevHash(String prevHash) { this.prevHash = prevHash; }
}
