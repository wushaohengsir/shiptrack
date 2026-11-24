package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.enums.AttemptStatus;
import com.shiptrack.contract.enums.RunTrigger;
import com.shiptrack.contract.enums.TransportStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * One query attempt plus its result snapshot.
 *
 * <p>{@code queriedAt} is an alias of {@code finishedAt} kept for display.
 * {@code rawPayload} is intentionally absent: it is returned only by troubleshooting endpoints.
 * Source: 接口文档_终稿.md §3.5</p>
 */
public record SnapshotDto(
        @JsonProperty("id") long id,
        @JsonProperty("container_id") long containerId,
        @JsonProperty("run_id") String runId,
        @JsonProperty("run_trigger") RunTrigger runTrigger,
        @JsonProperty("attempt_status") AttemptStatus attemptStatus,
        @JsonProperty("retry_count") int retryCount,
        @JsonProperty("queued_at") OffsetDateTime queuedAt,
        @JsonProperty("claimed_at") OffsetDateTime claimedAt,
        @JsonProperty("claim_token") String claimToken,
        @JsonProperty("started_at") OffsetDateTime startedAt,
        @JsonProperty("finished_at") OffsetDateTime finishedAt,
        @JsonProperty("queried_at") OffsetDateTime queriedAt,
        @JsonProperty("source") String source,
        @JsonProperty("success") Boolean success,
        @JsonProperty("transport_status") TransportStatus transportStatus,
        @JsonProperty("raw_status") String rawStatus,
        @JsonProperty("etas") List<Eta> etas,
        @JsonProperty("source_events") List<SourceEvent> sourceEvents,
        @JsonProperty("location") String location,
        @JsonProperty("vessel") String vessel,
        @JsonProperty("voyage") String voyage,
        @JsonProperty("pol") String pol,
        @JsonProperty("pod") String pod,
        @JsonProperty("final_destination") String finalDestination,
        @JsonProperty("content_hash") String contentHash,
        @JsonProperty("mapping_version") Integer mappingVersion,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("error_message") String errorMessage
) {
}
