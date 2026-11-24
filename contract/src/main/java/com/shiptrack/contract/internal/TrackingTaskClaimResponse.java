package com.shiptrack.contract.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.enums.RunTrigger;

import java.util.List;

/**
 * Response of {@code POST /internal/tracking-tasks}.
 *
 * <p>Source: 接口文档_终稿.md §5.22</p>
 */
public record TrackingTaskClaimResponse(
        @JsonProperty("run_id") String runId,
        @JsonProperty("trigger") RunTrigger trigger,
        @JsonProperty("items") List<ClaimedTaskDto> items
) {
}
