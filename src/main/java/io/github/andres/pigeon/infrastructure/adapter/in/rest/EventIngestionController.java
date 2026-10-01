package io.github.andres.pigeon.infrastructure.adapter.in.rest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.andres.pigeon.application.port.in.IngestEventCommand;
import io.github.andres.pigeon.application.port.in.IngestEventUseCase;
import io.github.andres.pigeon.application.port.in.QueryNotificationUseCase;
import io.github.andres.pigeon.domain.model.AuditRecord;
import io.github.andres.pigeon.domain.model.Notification;
import io.github.andres.pigeon.infrastructure.adapter.in.rest.dto.IngestEventRequest;
import io.github.andres.pigeon.infrastructure.adapter.in.rest.dto.IngestEventResponse;
import io.github.andres.pigeon.infrastructure.adapter.in.rest.dto.NotificationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Events & Notifications", description = "Transactional banking notification ingestion and query endpoints")
@SecurityRequirement(name = "bearerAuth")
public class EventIngestionController {

    private final IngestEventUseCase ingestEventUseCase;
    private final QueryNotificationUseCase queryNotificationUseCase;
    private final ObjectMapper objectMapper;

    public EventIngestionController(
            IngestEventUseCase ingestEventUseCase,
            QueryNotificationUseCase queryNotificationUseCase,
            ObjectMapper objectMapper
    ) {
        this.ingestEventUseCase = ingestEventUseCase;
        this.queryNotificationUseCase = queryNotificationUseCase;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/events")
    @Operation(summary = "Ingest business event", description = "Ingests a financial event and enqueues it for idempotent delivery")
    @ApiResponse(responseCode = "202", description = "Event accepted for delivery")
    @ApiResponse(responseCode = "200", description = "Idempotent replay of previously accepted event")
    @ApiResponse(responseCode = "400", description = "Invalid payload or sensitive data detected")
    @ApiResponse(responseCode = "409", description = "Idempotency key reused with different payload")
    public ResponseEntity<IngestEventResponse> ingestEvent(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody IngestEventRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        String clientId = extractClientId(jwt);
        String payloadHash = computePayloadHash(request);

        IngestEventCommand command = new IngestEventCommand(
                clientId,
                idempotencyKey,
                payloadHash,
                request.customerId(),
                request.eventType(),
                request.locale(),
                request.occurredAt(),
                request.data()
        );

        IngestEventCommand.IngestResult result = ingestEventUseCase.ingest(command);
        IngestEventResponse response = new IngestEventResponse(
                result.notificationId(),
                result.status(),
                result.priority()
        );

        if (result.isReplay()) {
            return ResponseEntity.ok()
                    .header("Idempotency-Replayed", "true")
                    .body(response);
        }

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/notifications/{id}")
    @Operation(summary = "Query notification", description = "Retrieves notification status, delivery attempts and audit trail")
    public ResponseEntity<NotificationResponse> getNotification(@PathVariable("id") UUID id) {
        Notification notification = queryNotificationUseCase.getNotification(id);
        List<AuditRecord> auditTrail = queryNotificationUseCase.getAuditTrail(id);

        List<NotificationResponse.DeliveryAttemptResponse> attemptResponses = notification.getAttempts().stream()
                .map(a -> new NotificationResponse.DeliveryAttemptResponse(
                        a.id(),
                        a.channel().name(),
                        a.attemptNo(),
                        a.outcome(),
                        a.providerRef(),
                        a.errorCode(),
                        a.latencyMs(),
                        a.createdAt()
                ))
                .toList();

        List<NotificationResponse.AuditRecordResponse> auditResponses = auditTrail.stream()
                .map(ar -> new NotificationResponse.AuditRecordResponse(
                        ar.id(),
                        ar.occurredAt(),
                        ar.actor(),
                        ar.action(),
                        ar.fromStatus() != null ? ar.fromStatus().name() : null,
                        ar.toStatus().name(),
                        ar.channel() != null ? ar.channel().name() : null,
                        ar.reason(),
                        ar.correlationId()
                ))
                .toList();

        NotificationResponse response = new NotificationResponse(
                notification.getId(),
                notification.getCustomerId().value(),
                notification.getEventType().name(),
                notification.getPriority(),
                notification.getLocale(),
                notification.getStatus(),
                notification.getFailureReason(),
                notification.getData(),
                notification.getCreatedAt(),
                notification.getUpdatedAt(),
                attemptResponses,
                auditResponses
        );

        return ResponseEntity.ok(response);
    }

    private String extractClientId(Jwt jwt) {
        if (jwt == null) {
            return "anonymous-producer";
        }
        String azp = jwt.getClaimAsString("azp");
        if (azp != null && !azp.isBlank()) {
            return azp;
        }
        String clientId = jwt.getClaimAsString("client_id");
        if (clientId != null && !clientId.isBlank()) {
            return clientId;
        }
        return jwt.getSubject() != null ? jwt.getSubject() : "unknown-client";
    }

    private String computePayloadHash(IngestEventRequest request) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] jsonBytes = objectMapper.writeValueAsBytes(request);
            byte[] hash = digest.digest(jsonBytes);
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException | JsonProcessingException e) {
            throw new IllegalStateException("Failed to calculate SHA-256 payload hash", e);
        }
    }
}
