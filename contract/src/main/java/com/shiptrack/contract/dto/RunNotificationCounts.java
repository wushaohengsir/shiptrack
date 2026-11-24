package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Notification counters inside a run result.
 *
 * <p>Source: 接口文档_终稿.md §5.21</p>
 */
public record RunNotificationCounts(
        @JsonProperty("created") int created,
        @JsonProperty("sent") int sent,
        @JsonProperty("failed") int failed
) {
}
