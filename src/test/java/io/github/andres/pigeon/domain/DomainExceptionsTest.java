package io.github.andres.pigeon.domain;

import io.github.andres.pigeon.domain.enums.NotificationStatus;
import io.github.andres.pigeon.domain.exception.CustomerOptedOutException;
import io.github.andres.pigeon.domain.exception.DuplicateEventException;
import io.github.andres.pigeon.domain.exception.InvalidStateTransitionException;
import io.github.andres.pigeon.domain.exception.NotificationNotFoundException;
import io.github.andres.pigeon.domain.exception.RateLimitExceededException;
import io.github.andres.pigeon.domain.exception.SensitiveDataException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DomainExceptionsTest {

    @Test
    @DisplayName("Should instantiate and verify all domain exceptions")
    void shouldVerifyDomainExceptions() {
        CustomerOptedOutException optOutEx = new CustomerOptedOutException("Customer opted out");
        assertThat(optOutEx.getMessage()).isEqualTo("Customer opted out");

        DuplicateEventException duplicateEx = new DuplicateEventException("client-1", "idem-1");
        assertThat(duplicateEx.getMessage()).contains("client-1").contains("idem-1");

        InvalidStateTransitionException stateEx = new InvalidStateTransitionException(NotificationStatus.PENDING, NotificationStatus.DELIVERED);
        assertThat(stateEx.getMessage()).contains("PENDING").contains("DELIVERED");

        UUID notificationId = UUID.randomUUID();
        NotificationNotFoundException notFoundEx = new NotificationNotFoundException(notificationId);
        assertThat(notFoundEx.getMessage()).contains(notificationId.toString());

        RateLimitExceededException rateLimitEx = new RateLimitExceededException("Rate limit reached", 45L);
        assertThat(rateLimitEx.getMessage()).isEqualTo("Rate limit reached");
        assertThat(rateLimitEx.getRetryAfterSeconds()).isEqualTo(45L);

        SensitiveDataException sensitiveEx = new SensitiveDataException("PAN detected");
        assertThat(sensitiveEx.getMessage()).isEqualTo("PAN detected");
    }
}
