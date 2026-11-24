package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.enums.RunTrigger;

import java.time.OffsetDateTime;

/**
 * Result of {@code GET /runs/{run_id}}.
 *
 * <p>Source: 接口文档_终稿.md §5.21</p>
 */
public record RunResultDto(
        @JsonProperty("run_id") String runId,
        @JsonProperty("trigger") RunTrigger trigger,
        @JsonProperty("status") String status,
        @JsonProperty("started_at") OffsetDateTime startedAt,
        @JsonProperty("finished_at") OffsetDateTime finishedAt,
        @JsonProperty("total") int total,
        @JsonProperty("queued") int queued,
        @JsonProperty("running") int running,
        @JsonProperty("success") int success,
        @JsonProperty("failed") int failed,
        @JsonProperty("skipped") int skipped,
        @JsonProperty("notifications") RunNotificationCounts notifications
) {
}
