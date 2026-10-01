package io.github.andres.pigeon.infrastructure;

import io.github.andres.pigeon.infrastructure.adapter.in.rest.validation.NoSensitiveDataValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NoSensitiveDataValidatorTest {

    @Test
    @DisplayName("Should detect and pass Luhn algorithm on test credit card numbers")
    void shouldDetectValidLuhnCardNumbers() {
        // Standard test card numbers (known Luhn valid numbers)
        String visaTestCard = "4532015112830366";
        String mastercardTestCard = "5425233430109903";

        assertThat(NoSensitiveDataValidator.passesLuhn(visaTestCard)).isTrue();
        assertThat(NoSensitiveDataValidator.passesLuhn(mastercardTestCard)).isTrue();
        assertThat(NoSensitiveDataValidator.containsLuhnCardNumber("Paid with card " + visaTestCard)).isTrue();
    }

    @Test
    @DisplayName("Should pass for safe 4-digit masked cards and accounts")
    void shouldPassForMaskedCardNumbers() {
        String safePayload = "Card last 4 is 4821 and account last 4 is 9012";
        assertThat(NoSensitiveDataValidator.containsLuhnCardNumber(safePayload)).isFalse();
    }

    @Test
    @DisplayName("Should fail Luhn check for random non-Luhn strings")
    void shouldFailNonLuhnNumbers() {
        String invalidLuhn = "4532015112830367"; // Last digit changed
        assertThat(NoSensitiveDataValidator.passesLuhn(invalidLuhn)).isFalse();
    }
}
