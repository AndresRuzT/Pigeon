package io.github.andres.pigeon.domain;

import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.domain.enums.Priority;
import io.github.andres.pigeon.domain.policy.PriorityPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PriorityPolicyTest {

    @Test
    @DisplayName("Should assign HIGH priority to OTP and FRAUD events")
    void shouldAssignHighPriorityToSecurityEvents() {
        assertThat(PriorityPolicy.determinePriority(EventType.OTP_REQUESTED)).isEqualTo(Priority.HIGH);
        assertThat(PriorityPolicy.determinePriority(EventType.FRAUD_SUSPECTED)).isEqualTo(Priority.HIGH);
    }

    @Test
    @DisplayName("Should assign LOW priority to informational events")
    void shouldAssignLowPriorityToInformationalEvents() {
        assertThat(PriorityPolicy.determinePriority(EventType.TRANSFER_COMPLETED)).isEqualTo(Priority.LOW);
        assertThat(PriorityPolicy.determinePriority(EventType.PURCHASE_DECLINED)).isEqualTo(Priority.LOW);
        assertThat(PriorityPolicy.determinePriority(EventType.PAYMENT_REMINDER)).isEqualTo(Priority.LOW);
    }
}
