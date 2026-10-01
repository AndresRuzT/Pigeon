package io.github.andres.pigeon.infrastructure.adapter.out.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "customer_preference")
public class CustomerPreferenceJpaEntity {

    @Id
    @Column(name = "customer_id", length = 64)
    private String customerId;

    @Column(name = "allowed_channels", length = 64, nullable = false)
    private String allowedChannels;

    @Column(name = "preferred_channel_order", length = 64, nullable = false)
    private String preferredChannelOrder;

    @Column(name = "opt_out_categories", length = 256, nullable = false)
    private String optOutCategories;

    @Column(name = "time_zone", length = 64, nullable = false)
    private String timeZone;

    @Column(name = "quiet_hours_enabled", nullable = false)
    private boolean quietHoursEnabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public CustomerPreferenceJpaEntity() {}

    public String getCustomerId() { return customerId; }
    public void setCustomerId(String customerId) { this.customerId = customerId; }
    public String getAllowedChannels() { return allowedChannels; }
    public void setAllowedChannels(String allowedChannels) { this.allowedChannels = allowedChannels; }
    public String getPreferredChannelOrder() { return preferredChannelOrder; }
    public void setPreferredChannelOrder(String preferredChannelOrder) { this.preferredChannelOrder = preferredChannelOrder; }
    public String getOptOutCategories() { return optOutCategories; }
    public void setOptOutCategories(String optOutCategories) { this.optOutCategories = optOutCategories; }
    public String getTimeZone() { return timeZone; }
    public void setTimeZone(String timeZone) { this.timeZone = timeZone; }
    public boolean isQuietHoursEnabled() { return quietHoursEnabled; }
    public void setQuietHoursEnabled(boolean quietHoursEnabled) { this.quietHoursEnabled = quietHoursEnabled; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
