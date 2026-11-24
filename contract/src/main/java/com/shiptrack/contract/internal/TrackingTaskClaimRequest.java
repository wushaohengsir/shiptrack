package com.shiptrack.contract.internal;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Request body of {@code POST /internal/tracking-tasks}; {@code limit} defaults to 1, max 20.
 *
 * <p>Source: 接口文档_终稿.md §5.22</p>
 */
public record TrackingTaskClaimRequest(
        @JsonProperty("run_id") String runId,
        @JsonProperty("limit") Integer limit
) {
}
