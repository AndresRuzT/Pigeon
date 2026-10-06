package io.github.andres.pigeon.infrastructure.adapter.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.andres.pigeon.application.port.in.ProcessWebhookReceiptUseCase;
import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.enums.NotificationStatus;
import io.github.andres.pigeon.infrastructure.adapter.in.rest.dto.WebhookReceiptRequest;
import io.github.andres.pigeon.infrastructure.adapter.in.rest.dto.WebhookReceiptResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

@RestController
@RequestMapping("/api/v1/webhooks")
@Tag(name = "Delivery Webhooks", description = "Endpoints for provider delivery receipts with HMAC verification")
public class WebhookReceiptController {

    private static final Logger log = LoggerFactory.getLogger(WebhookReceiptController.class);

    private final ProcessWebhookReceiptUseCase processWebhookReceiptUseCase;
    private final ObjectMapper objectMapper;
    private final String webhookSecret;

    public WebhookReceiptController(
            ProcessWebhookReceiptUseCase processWebhookReceiptUseCase,
            ObjectMapper objectMapper,
            @Value("${pigeon.webhooks.secret:pigeon_dev_webhook_secret_key_1234567890}") String webhookSecret
    ) {
        this.processWebhookReceiptUseCase = processWebhookReceiptUseCase;
        this.objectMapper = objectMapper;
        this.webhookSecret = webhookSecret;
    }

    @PostMapping(value = "/{channel}/receipts", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Process delivery receipt", description = "Receives signed delivery receipts from providers via HMAC-SHA256")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Receipt processed successfully"),
            @ApiResponse(responseCode = "400", description = "Invalid payload or channel"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid HMAC signature"),
            @ApiResponse(responseCode = "404", description = "Notification not found"),
            @ApiResponse(responseCode = "409", description = "State conflict")
    })
    public ResponseEntity<?> receiveReceipt(
            @Parameter(description = "Channel name (PUSH, SMS, EMAIL)") @PathVariable String channel,
            @Parameter(description = "Hex HMAC-SHA256 signature of the raw payload") @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestBody byte[] rawPayload
    ) {
        if (!verifyHmac(rawPayload, signature)) {
            log.warn("Unauthorized webhook delivery receipt rejected: invalid or missing HMAC signature");
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Invalid or missing webhook HMAC signature header (X-Signature)");
            pd.setType(URI.create("https://pigeon.bank.internal/errors/invalid-webhook-signature"));
            pd.setTitle("Invalid Webhook Signature");
            pd.setProperty("timestamp", Instant.now());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(pd);
        }

        Channel channelEnum;
        try {
            channelEnum = Channel.valueOf(channel.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Unknown channel: " + channel);
            pd.setType(URI.create("https://pigeon.bank.internal/errors/invalid-channel"));
            pd.setTitle("Invalid Channel");
            pd.setProperty("timestamp", Instant.now());
            return ResponseEntity.badRequest().body(pd);
        }

        WebhookReceiptRequest request;
        try {
            request = objectMapper.readValue(rawPayload, WebhookReceiptRequest.class);
        } catch (Exception e) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Malformed JSON webhook receipt payload: " + e.getMessage());
            pd.setType(URI.create("https://pigeon.bank.internal/errors/validation-error"));
            pd.setTitle("Malformed Payload");
            pd.setProperty("timestamp", Instant.now());
            return ResponseEntity.badRequest().body(pd);
        }

        if (request.notificationId() == null || request.status() == null || request.status().isBlank()) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "notificationId and status are required fields");
            pd.setType(URI.create("https://pigeon.bank.internal/errors/validation-error"));
            pd.setTitle("Missing Required Fields");
            pd.setProperty("timestamp", Instant.now());
            return ResponseEntity.badRequest().body(pd);
        }

        NotificationStatus statusEnum;
        try {
            statusEnum = NotificationStatus.valueOf(request.status().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Invalid receipt status: " + request.status() + " (expected DELIVERED or FAILED)");
            pd.setType(URI.create("https://pigeon.bank.internal/errors/invalid-status"));
            pd.setTitle("Invalid Status");
            pd.setProperty("timestamp", Instant.now());
            return ResponseEntity.badRequest().body(pd);
        }

        var command = new ProcessWebhookReceiptUseCase.ReceiptCommand(
                channelEnum,
                request.notificationId(),
                request.providerRef(),
                statusEnum,
                request.errorCode(),
                request.reason(),
                request.occurredAt()
        );

        var result = processWebhookReceiptUseCase.processReceipt(command);
        return ResponseEntity.ok(new WebhookReceiptResponse(result.notificationId(), result.status().name(), result.processed()));
    }

    private boolean verifyHmac(byte[] payload, String signature) {
        if (signature == null || signature.isBlank() || payload == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKey);
            byte[] expectedHmac = mac.doFinal(payload);
            String expectedHex = HexFormat.of().formatHex(expectedHmac);
            return MessageDigest.isEqual(expectedHex.getBytes(StandardCharsets.UTF_8), signature.trim().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Error computing HMAC signature verification", e);
            return false;
        }
    }
}
