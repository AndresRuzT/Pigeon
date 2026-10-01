package io.github.andres.pigeon.domain.model;

import io.github.andres.pigeon.domain.enums.Channel;
import io.github.andres.pigeon.domain.enums.EventType;
import io.github.andres.pigeon.domain.vo.CustomerId;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Domain model representing a customer's communication preferences and quiet-hours window.
 */
public class CustomerPreference {

    private final CustomerId customerId;
    private final List<Channel> allowedChannels;
    private final List<Channel> preferredChannelOrder;
    private final Set<String> optOutCategories;
    private final ZoneId timeZone;
    private final boolean quietHoursEnabled;
    private final Instant createdAt;
    private Instant updatedAt;

    public CustomerPreference(
            CustomerId customerId,
            List<Channel> allowedChannels,
            List<Channel> preferredChannelOrder,
            Set<String> optOutCategories,
            ZoneId timeZone,
            boolean quietHoursEnabled,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.customerId = Objects.requireNonNull(customerId, "customerId cannot be null");
        this.allowedChannels = allowedChannels != null
                ? List.copyOf(allowedChannels)
                : List.of(Channel.PUSH, Channel.SMS, Channel.EMAIL);
        this.preferredChannelOrder = preferredChannelOrder != null
                ? List.copyOf(preferredChannelOrder)
                : List.of(Channel.PUSH, Channel.SMS, Channel.EMAIL);
        this.optOutCategories = optOutCategories != null ? Set.copyOf(optOutCategories) : Set.of();
        this.timeZone = timeZone != null ? timeZone : ZoneId.of("UTC");
        this.quietHoursEnabled = quietHoursEnabled;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt cannot be null");
    }

    public static CustomerPreference defaultPreference(CustomerId customerId, Instant now) {
        return new CustomerPreference(
                customerId,
                List.of(Channel.PUSH, Channel.SMS, Channel.EMAIL),
                List.of(Channel.PUSH, Channel.SMS, Channel.EMAIL),
                Set.of(),
                ZoneId.of("UTC"),
                true,
                now,
                now
        );
    }

    public boolean isOptedOut(EventType eventType) {
        Objects.requireNonNull(eventType, "eventType cannot be null");
        return optOutCategories.contains(eventType.name());
    }

    public boolean isChannelAllowed(Channel channel) {
        Objects.requireNonNull(channel, "channel cannot be null");
        return allowedChannels.contains(channel);
    }

    public CustomerId getCustomerId() {
        return customerId;
    }

    public List<Channel> getAllowedChannels() {
        return Collections.unmodifiableList(allowedChannels);
    }

    public List<Channel> getPreferredChannelOrder() {
        return Collections.unmodifiableList(preferredChannelOrder);
    }

    public Set<String> getOptOutCategories() {
        return Collections.unmodifiableSet(optOutCategories);
    }

    public ZoneId getTimeZone() {
        return timeZone;
    }

    public boolean isQuietHoursEnabled() {
        return quietHoursEnabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
