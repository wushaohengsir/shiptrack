package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Public view of {@code config/config.yaml}. Never carries webhook values, only a flag.
 *
 * <p>Source: 接口文档_终稿.md §5.15</p>
 */
public record AppConfigDto(
        @JsonProperty("daily_query_time") String dailyQueryTime,
        @JsonProperty("timezone") String timezone,
        @JsonProperty("eta_delay_threshold_hours") int etaDelayThresholdHours,
        @JsonProperty("eta_delay_threshold_days") int etaDelayThresholdDays,
        @JsonProperty("eta_early_threshold_hours") int etaEarlyThresholdHours,
        @JsonProperty("eta_early_threshold_days") int etaEarlyThresholdDays,
        @JsonProperty("stale_after_hours") int staleAfterHours,
        @JsonProperty("arriving_soon_days") int arrivingSoonDays,
        @JsonProperty("notify_on_first_eta") boolean notifyOnFirstEta,
        @JsonProperty("notify_on_status_change") boolean notifyOnStatusChange,
        @JsonProperty("max_retry_per_run") int maxRetryPerRun,
        @JsonProperty("snapshot_retention_days") int snapshotRetentionDays,
        @JsonProperty("raw_payload_retention_days") int rawPayloadRetentionDays,
        @JsonProperty("feishu_webhook_configured") boolean feishuWebhookConfigured
) {
}
