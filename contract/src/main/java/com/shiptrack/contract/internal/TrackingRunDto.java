package com.shiptrack.contract.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.enums.RunTrigger;

import java.time.OffsetDateTime;

/**
 * A run that still has queued tasks.
 *
 * <p>Source: 接口文档_终稿.md §5.24</p>
 */
public record TrackingRunDto(
        @JsonProperty("run_id") String runId,
        @JsonProperty("trigger") RunTrigger trigger,
        @JsonProperty("queued_count") int queuedCount,
        @JsonProperty("oldest_queued_at") OffsetDateTime oldestQueuedAt
) {
}
