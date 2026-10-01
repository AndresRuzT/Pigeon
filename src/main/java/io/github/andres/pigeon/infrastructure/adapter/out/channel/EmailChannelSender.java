package io.github.andres.pigeon.infrastructure.adapter.out.channel;

import io.github.andres.pigeon.application.port.out.EmailSenderPort;
import io.github.andres.pigeon.domain.model.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
public class EmailChannelSender implements EmailSenderPort {

    private static final Logger log = LoggerFactory.getLogger(EmailChannelSender.class);

    private final JavaMailSender mailSender;

    public EmailChannelSender(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public EmailSendResult sendEmail(String recipientEmail, Notification notification) {
        long startTime = System.currentTimeMillis();
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom("alerts@pigeon.bank.internal");
            message.setTo(recipientEmail);
            message.setSubject("[Pigeon Alert] " + notification.getEventType().name());

            String body = buildEmailBody(notification);
            message.setText(body);

            mailSender.send(message);

            long latencyMs = System.currentTimeMillis() - startTime;
            String providerRef = "mailhog-" + UUID.randomUUID();
            log.info("Email sent successfully to {} with providerRef {} in {} ms", recipientEmail, providerRef, latencyMs);
            return EmailSendResult.ok(providerRef, latencyMs);
        } catch (Exception e) {
            long latencyMs = System.currentTimeMillis() - startTime;
            log.error("Failed to send email to {}: {}", recipientEmail, e.getMessage());
            return EmailSendResult.error(e.getClass().getSimpleName() + ": " + e.getMessage(), latencyMs);
        }
    }

    private String buildEmailBody(Notification notification) {
        StringBuilder sb = new StringBuilder();
        sb.append("Dear Customer,\n\n");
        sb.append("Important alert regarding your banking account:\n");
        sb.append("Event: ").append(notification.getEventType().name()).append("\n");
        sb.append("Notification Reference: ").append(notification.getId()).append("\n");

        Map<String, Object> data = notification.getData();
        if (data != null && !data.isEmpty()) {
            sb.append("\nDetails:\n");
            data.forEach((key, value) -> {
                // Ensure sensitive field values are represented cleanly
                sb.append(" - ").append(key).append(": ").append(value).append("\n");
            });
        }

        sb.append("\nThank you for banking with us.\nPigeon Security Team\n");
        return sb.toString();
    }
}
