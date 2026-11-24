package com.shiptrack.contract.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.enums.TrackedMilestone;

import java.time.OffsetDateTime;

/**
 * One task handed to the collector.
 *
 * <p>Source: 接口文档_终稿.md §5.22</p>
 */
public record ClaimedTaskDto(
        @JsonProperty("container_id") long containerId,
        @JsonProperty("container_no") String containerNo,
        @JsonProperty("carrier") String carrier,
        @JsonProperty("tracked_milestone") TrackedMilestone trackedMilestone,
        @JsonProperty("baseline_snapshot_id") Long baselineSnapshotId,
        @JsonProperty("last_success_at") OffsetDateTime lastSuccessAt,
        @JsonProperty("claim_token") String claimToken,
        @JsonProperty("max_retry") int maxRetry
) {
}
