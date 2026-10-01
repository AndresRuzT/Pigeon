package io.github.andres.pigeon.domain;

import io.github.andres.pigeon.domain.exception.SensitiveDataException;
import io.github.andres.pigeon.domain.vo.MaskedCardNumber;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MaskedCardNumberTest {

    @Test
    @DisplayName("Should accept valid 4 digits and format as **** XXXX")
    void shouldAcceptFourDigits() {
        MaskedCardNumber masked = MaskedCardNumber.ofLast4("4821");
        assertThat(masked.value()).isEqualTo("**** 4821");
        assertThat(masked.last4()).isEqualTo("4821");
    }

    @Test
    @DisplayName("Should accept already masked value '**** 4821'")
    void shouldAcceptAlreadyMasked() {
        MaskedCardNumber masked = new MaskedCardNumber("**** 4821");
        assertThat(masked.value()).isEqualTo("**** 4821");
        assertThat(masked.last4()).isEqualTo("4821");
    }

    @Test
    @DisplayName("Should reject full card number with SensitiveDataException")
    void shouldRejectFullCardNumber() {
        assertThatThrownBy(() -> new MaskedCardNumber("4532015112834821"))
                .isInstanceOf(SensitiveDataException.class)
                .hasMessageContaining("Invalid masked card number");
    }

    @Test
    @DisplayName("Should reject invalid length digits")
    void shouldRejectInvalidLength() {
        assertThatThrownBy(() -> new MaskedCardNumber("482"))
                .isInstanceOf(SensitiveDataException.class);
        assertThatThrownBy(() -> new MaskedCardNumber("48210"))
                .isInstanceOf(SensitiveDataException.class);
    }
}
