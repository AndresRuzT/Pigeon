package io.github.andres.pigeon.infrastructure;

import io.github.andres.pigeon.application.port.out.AuditLogPort;
import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.enums.NotificationStatus;
import io.github.andres.pigeon.domain.model.AuditRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Testcontainers
class AuditImmutabilityIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("pigeon")
            .withUsername("pigeon_test")
            .withPassword("pigeon_test");

    @Container
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.rabbitmq.host", rabbitmq::getHost);
        registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", redis::getFirstMappedPort);
        registry.add("pigeon.security.jwt.public-key-location", () -> "classpath:certs/app.pub");
        registry.add("management.health.mail.enabled", () -> "false");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuditLogPort auditLogPort;

    @Test
    @DisplayName("Should reject UPDATE statements on audit_log at database trigger level")
    void shouldRejectUpdateOnAuditLog() {
        UUID id = UUID.randomUUID();
        UUID notificationId = UUID.randomUUID();
        Instant now = Instant.now();

        AuditRecord record = new AuditRecord(
                id, now, notificationId, "cus_123", "system", "TEST_ACTION",
                null, NotificationStatus.PENDING, Channel.SMS, "v1", "Initial record", "corr-1", null
        );
        auditLogPort.append(record);

        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE audit_log SET actor = 'attacker' WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("Should reject DELETE statements on audit_log at database trigger level")
    void shouldRejectDeleteOnAuditLog() {
        UUID id = UUID.randomUUID();
        UUID notificationId = UUID.randomUUID();
        Instant now = Instant.now();

        AuditRecord record = new AuditRecord(
                id, now, notificationId, "cus_123", "system", "TEST_ACTION",
                null, NotificationStatus.PENDING, Channel.SMS, "v1", "Initial record", "corr-2", null
        );
        auditLogPort.append(record);

        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM audit_log WHERE id = ?", id))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("Should reject TRUNCATE statements on audit_log at database trigger level")
    void shouldRejectTruncateOnAuditLog() {
        assertThatThrownBy(() -> jdbcTemplate.execute("TRUNCATE TABLE audit_log"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("TRUNCATE is forbidden");
    }

    @Test
    @DisplayName("Should form a cryptographic tamper-evident prev_hash chain across multiple audit records")
    void shouldFormCryptographicHashChain() {
        UUID notificationId = UUID.randomUUID();
        Instant now = Instant.now();

        AuditRecord first = new AuditRecord(
                UUID.randomUUID(), now, notificationId, "cus_chain", "system", "NOTIFICATION_ACCEPTED",
                null, NotificationStatus.PENDING, null, null, "Created", "corr-chain", null
        );
        AuditRecord second = new AuditRecord(
                UUID.randomUUID(), now.plusMillis(100), notificationId, "cus_chain", "system", "NOTIFICATION_SENT",
                NotificationStatus.PENDING, NotificationStatus.SENT, Channel.SMS, "v1", "Dispatched", "corr-chain", null
        );
        AuditRecord third = new AuditRecord(
                UUID.randomUUID(), now.plusMillis(200), notificationId, "cus_chain", "system", "NOTIFICATION_DELIVERED",
                NotificationStatus.SENT, NotificationStatus.DELIVERED, Channel.SMS, "v1", "Delivered", "corr-chain", null
        );

        auditLogPort.append(first);
        auditLogPort.append(second);
        auditLogPort.append(third);

        List<AuditRecord> records = auditLogPort.findByNotificationId(notificationId);
        assertThat(records).hasSize(3);

        String hash1 = records.get(0).prevHash();
        String hash2 = records.get(1).prevHash();
        String hash3 = records.get(2).prevHash();

        assertThat(hash1).isNotNull().isNotBlank();
        assertThat(hash2).isNotNull().isNotBlank();
        assertThat(hash3).isNotNull().isNotBlank();

        assertThat(hash1).isNotEqualTo(hash2);
        assertThat(hash2).isNotEqualTo(hash3);
    }
}
