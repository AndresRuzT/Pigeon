package io.github.andres.pigeon.domain;

import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.domain.model.CustomerPreference;
import io.github.andres.pigeon.domain.vo.CustomerId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerPreferenceTest {

    @Test
    @DisplayName("Should create default preference with standard fallback and quiet hours enabled")
    void shouldCreateDefaultPreference() {
        Instant now = Instant.now();
        CustomerPreference pref = CustomerPreference.defaultPreference(CustomerId.of("cus_123"), now);

        assertThat(pref.getCustomerId().value()).isEqualTo("cus_123");
        assertThat(pref.getAllowedChannels()).containsExactly(Channel.PUSH, Channel.SMS, Channel.EMAIL);
        assertThat(pref.getPreferredChannelOrder()).containsExactly(Channel.PUSH, Channel.SMS, Channel.EMAIL);
        assertThat(pref.getOptOutCategories()).isEmpty();
        assertThat(pref.getTimeZone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(pref.isQuietHoursEnabled()).isTrue();
    }

    @Test
    @DisplayName("Should correctly evaluate opt-out categories and allowed channels")
    void shouldEvaluateOptOutAndAllowedChannels() {
        CustomerPreference pref = new CustomerPreference(
                CustomerId.of("cus_456"),
                List.of(Channel.SMS, Channel.EMAIL),
                List.of(Channel.SMS, Channel.EMAIL),
                Set.of("PAYMENT_REMINDER"),
                ZoneId.of("America/Bogota"),
                true,
                Instant.now(),
                Instant.now()
        );

        assertThat(pref.isChannelAllowed(Channel.SMS)).isTrue();
        assertThat(pref.isChannelAllowed(Channel.EMAIL)).isTrue();
        assertThat(pref.isChannelAllowed(Channel.PUSH)).isFalse();

        assertThat(pref.isOptedOut(EventType.PAYMENT_REMINDER)).isTrue();
        assertThat(pref.isOptedOut(EventType.TRANSFER_COMPLETED)).isFalse();
    }
}
