package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Partial update body of {@code PATCH /config}; null fields are left unchanged.
 * Webhook URL and secret are deliberately absent and must be rejected if sent.
 *
 * <p>Source: 接口文档_终稿.md §5.16</p>
 */
public record AppConfigPatchDto(
        @JsonProperty("daily_query_time") String dailyQueryTime,
        @JsonProperty("timezone") String timezone,
        @JsonProperty("eta_delay_threshold_hours") Integer etaDelayThresholdHours,
        @JsonProperty("eta_delay_threshold_days") Integer etaDelayThresholdDays,
        @JsonProperty("eta_early_threshold_hours") Integer etaEarlyThresholdHours,
        @JsonProperty("eta_early_threshold_days") Integer etaEarlyThresholdDays,
        @JsonProperty("stale_after_hours") Integer staleAfterHours,
        @JsonProperty("arriving_soon_days") Integer arrivingSoonDays,
        @JsonProperty("notify_on_first_eta") Boolean notifyOnFirstEta,
        @JsonProperty("notify_on_status_change") Boolean notifyOnStatusChange,
        @JsonProperty("max_retry_per_run") Integer maxRetryPerRun,
        @JsonProperty("snapshot_retention_days") Integer snapshotRetentionDays,
        @JsonProperty("raw_payload_retention_days") Integer rawPayloadRetentionDays
) {
}
