package io.github.andres.pigeon.domain.model;

import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.domain.enums.FailureReason;
import io.github.andres.pigeon.domain.enums.NotificationStatus;
import io.github.andres.pigeon.domain.enums.Priority;
import io.github.andres.pigeon.domain.exception.InvalidStateTransitionException;
import io.github.andres.pigeon.domain.policy.PriorityPolicy;
import io.github.andres.pigeon.domain.vo.CustomerId;
import io.github.andres.pigeon.domain.vo.IdempotencyKey;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class Notification {
    private final UUID id;
    private final String clientId;
    private final IdempotencyKey idempotencyKey;
    private final String payloadHash;
    private final CustomerId customerId;
    private final EventType eventType;
    private final Priority priority;
    private final String locale;
    private NotificationStatus status;
    private FailureReason failureReason;
    private String templateId;
    private String templateVersion;
    private final Map<String, Object> data;
    private Instant scheduledAt;
    private final Instant createdAt;
    private Instant updatedAt;
    private Long version;
    private final List<DeliveryAttempt> attempts;

    public Notification(
            UUID id,
            String clientId,
            IdempotencyKey idempotencyKey,
            String payloadHash,
            CustomerId customerId,
            EventType eventType,
            Priority priority,
            String locale,
            NotificationStatus status,
            FailureReason failureReason,
            String templateId,
            String templateVersion,
            Map<String, Object> data,
            Instant scheduledAt,
            Instant createdAt,
            Instant updatedAt,
            Long version,
            List<DeliveryAttempt> attempts
    ) {
        this.id = Objects.requireNonNull(id, "id cannot be null");
        this.clientId = Objects.requireNonNull(clientId, "clientId cannot be null");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
        this.payloadHash = Objects.requireNonNull(payloadHash, "payloadHash cannot be null");
        this.customerId = Objects.requireNonNull(customerId, "customerId cannot be null");
        this.eventType = Objects.requireNonNull(eventType, "eventType cannot be null");
        this.priority = priority != null ? priority : PriorityPolicy.determinePriority(eventType);
        this.locale = locale != null && !locale.isBlank() ? locale : "en";
        this.status = Objects.requireNonNull(status, "status cannot be null");
        this.failureReason = failureReason;
        this.templateId = templateId;
        this.templateVersion = templateVersion;
        this.data = data != null ? Map.copyOf(data) : Map.of();
        this.scheduledAt = scheduledAt;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt cannot be null");
        this.version = version;
        this.attempts = new ArrayList<>(attempts != null ? attempts : List.of());
    }

    public static Notification createPending(
            String clientId,
            IdempotencyKey idempotencyKey,
            String payloadHash,
            CustomerId customerId,
            EventType eventType,
            String locale,
            Map<String, Object> data,
            Instant now
    ) {
        UUID newId = UUID.randomUUID();
        Priority determinedPriority = PriorityPolicy.determinePriority(eventType);
        return new Notification(
                newId,
                clientId,
                idempotencyKey,
                payloadHash,
                customerId,
                eventType,
                determinedPriority,
                locale,
                NotificationStatus.PENDING,
                null,
                null,
                null,
                data,
                null,
                now,
                now,
                null,
                new ArrayList<>()
        );
    }

    public AuditRecord markSent(Channel channel, String providerRef, long latencyMs, String actor, String correlationId, Instant now) {
        assertNotTerminal();
        if (this.status != NotificationStatus.PENDING) {
            throw new InvalidStateTransitionException(this.status, NotificationStatus.SENT);
        }
        NotificationStatus previousStatus = this.status;
        this.status = NotificationStatus.SENT;
        this.updatedAt = now;

        int attemptNo = this.attempts.size() + 1;
        this.attempts.add(DeliveryAttempt.success(this.id, channel, attemptNo, providerRef, latencyMs, now));

        return AuditRecord.create(
                this.id,
                this.customerId.value(),
                actor != null ? actor : "system",
                "NOTIFICATION_SENT",
                previousStatus,
                NotificationStatus.SENT,
                channel,
                "Provider accepted message with ref " + providerRef,
                correlationId,
                now
        );
    }

    public AuditRecord markDelivered(Channel channel, String actor, String correlationId, Instant now) {
        assertNotTerminal();
        if (this.status != NotificationStatus.SENT) {
            throw new InvalidStateTransitionException(this.status, NotificationStatus.DELIVERED);
        }
        NotificationStatus previousStatus = this.status;
        this.status = NotificationStatus.DELIVERED;
        this.updatedAt = now;

        return AuditRecord.create(
                this.id,
                this.customerId.value(),
                actor != null ? actor : "system",
                "NOTIFICATION_DELIVERED",
                previousStatus,
                NotificationStatus.DELIVERED,
                channel,
                "Delivery confirmed",
                correlationId,
                now
        );
    }

    public AuditRecord markFailed(FailureReason reason, Channel channel, String actor, String correlationId, String detail, Instant now) {
        assertNotTerminal();
        NotificationStatus previousStatus = this.status;
        this.status = NotificationStatus.FAILED;
        this.failureReason = Objects.requireNonNull(reason, "FailureReason cannot be null");
        this.updatedAt = now;

        return AuditRecord.create(
                this.id,
                this.customerId.value(),
                actor != null ? actor : "system",
                "NOTIFICATION_FAILED",
                previousStatus,
                NotificationStatus.FAILED,
                channel,
                detail != null ? detail : reason.name(),
                correlationId,
                now
        );
    }

    public void recordFailedAttempt(Channel channel, String errorCode, long latencyMs, Instant now) {
        int attemptNo = this.attempts.size() + 1;
        this.attempts.add(DeliveryAttempt.failure(this.id, channel, attemptNo, errorCode, latencyMs, now));
        this.updatedAt = now;
    }

    private void assertNotTerminal() {
        if (this.status == NotificationStatus.DELIVERED || this.status == NotificationStatus.FAILED) {
            throw new InvalidStateTransitionException(this.status, this.status);
        }
    }

    // Getters
    public UUID getId() { return id; }
    public String getClientId() { return clientId; }
    public IdempotencyKey getIdempotencyKey() { return idempotencyKey; }
    public String getPayloadHash() { return payloadHash; }
    public CustomerId getCustomerId() { return customerId; }
    public EventType getEventType() { return eventType; }
    public Priority getPriority() { return priority; }
    public String getLocale() { return locale; }
    public NotificationStatus getStatus() { return status; }
    public FailureReason getFailureReason() { return failureReason; }
    public String getTemplateId() { return templateId; }
    public String getTemplateVersion() { return templateVersion; }
    public Map<String, Object> getData() { return data; }
    public Instant getScheduledAt() { return scheduledAt; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
    public List<DeliveryAttempt> getAttempts() { return Collections.unmodifiableList(attempts); }

    public boolean isTerminal() {
        return status == NotificationStatus.DELIVERED || status == NotificationStatus.FAILED;
    }
}
