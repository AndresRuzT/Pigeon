package io.github.andres.pigeon.domain;

import io.github.andres.pigeon.domain.exception.SensitiveDataException;
import io.github.andres.pigeon.domain.vo.MaskedAccountNumber;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaskedAccountNumberTest {

    @Test
    @DisplayName("Should accept valid 4 digits and format as **** XXXX")
    void shouldAcceptFourDigits() {
        MaskedAccountNumber masked = MaskedAccountNumber.ofLast4("9012");
        assertThat(masked.value()).isEqualTo("**** 9012");
        assertThat(masked.last4()).isEqualTo("9012");
    }

    @Test
    @DisplayName("Should reject full account number with SensitiveDataException")
    void shouldRejectFullAccountNumber() {
        assertThatThrownBy(() -> new MaskedAccountNumber("123456789012"))
                .isInstanceOf(SensitiveDataException.class)
                .hasMessageContaining("Invalid masked account number");
    }
}
