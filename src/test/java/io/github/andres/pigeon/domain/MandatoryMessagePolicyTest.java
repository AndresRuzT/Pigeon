package io.github.andres.pigeon.domain;

import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.domain.policy.MandatoryMessagePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MandatoryMessagePolicyTest {

    @Test
    @DisplayName("Should flag OTP and FRAUD as mandatory security messages")
    void shouldIdentifyMandatorySecurityMessages() {
        assertThat(MandatoryMessagePolicy.isMandatory(EventType.OTP_REQUESTED)).isTrue();
        assertThat(MandatoryMessagePolicy.isMandatory(EventType.FRAUD_SUSPECTED)).isTrue();
    }

    @Test
    @DisplayName("Should flag transfers and reminders as non-mandatory")
    void shouldIdentifyNonMandatoryMessages() {
        assertThat(MandatoryMessagePolicy.isMandatory(EventType.TRANSFER_COMPLETED)).isFalse();
        assertThat(MandatoryMessagePolicy.isMandatory(EventType.PURCHASE_DECLINED)).isFalse();
        assertThat(MandatoryMessagePolicy.isMandatory(EventType.PAYMENT_REMINDER)).isFalse();
    }
}
