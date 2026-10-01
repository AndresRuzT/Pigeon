package io.github.andres.pigeon.domain;

import io.github.andres.pigeon.domain.exception.NotificationNotFoundException;
import io.github.andres.pigeon.domain.model.CustomerContact;
import io.github.andres.pigeon.domain.vo.CustomerId;
import io.github.andres.pigeon.domain.vo.Destination;
import io.github.andres.pigeon.domain.vo.IdempotencyKey;
import io.github.andres.pigeon.domain.vo.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValueObjectsTest {

    @Test
    @DisplayName("Should create and validate Money VO")
    void shouldCreateMoney() {
        Money m1 = Money.of(new BigDecimal("150.50"), "USD");
        Money m2 = Money.of("150.50", "USD");

        assertThat(m1.amount()).isEqualTo(new BigDecimal("150.50"));
        assertThat(m1.currency()).isEqualTo(Currency.getInstance("USD"));
        assertThat(m1).isEqualTo(m2);

        assertThatThrownBy(() -> Money.of(new BigDecimal("-1.00"), "USD"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should create and validate Destination VO")
    void shouldCreateDestination() {
        Destination destination = Destination.of("user@example.com");
        assertThat(destination.value()).isEqualTo("user@example.com");

        assertThatThrownBy(() -> Destination.of(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should create and validate CustomerId VO")
    void shouldCreateCustomerId() {
        CustomerId customerId = CustomerId.of("cus_123");
        assertThat(customerId.value()).isEqualTo("cus_123");

        assertThatThrownBy(() -> CustomerId.of(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should create and validate IdempotencyKey VO")
    void shouldCreateIdempotencyKey() {
        IdempotencyKey key = IdempotencyKey.of("idemp-key-1");
        assertThat(key.value()).isEqualTo("idemp-key-1");

        assertThatThrownBy(() -> IdempotencyKey.of("   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Should create and validate CustomerContact record")
    void shouldCreateCustomerContact() {
        CustomerContact contact = CustomerContact.of("cus_1", "email@example.com", "+12345", "token1");
        assertThat(contact.customerId().value()).isEqualTo("cus_1");
        assertThat(contact.email()).isEqualTo("email@example.com");
        assertThat(contact.phone()).isEqualTo("+12345");
        assertThat(contact.pushToken()).isEqualTo("token1");
    }

    @Test
    @DisplayName("Should create and format NotificationNotFoundException")
    void shouldCreateNotificationNotFoundException() {
        UUID id = UUID.randomUUID();
        NotificationNotFoundException ex = new NotificationNotFoundException(id);
        assertThat(ex.getMessage()).contains(id.toString());
    }
}
