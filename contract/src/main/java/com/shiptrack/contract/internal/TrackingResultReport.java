package com.shiptrack.contract.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.dto.Eta;
import com.shiptrack.contract.dto.SourceEvent;
import com.shiptrack.contract.enums.TransportStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Report body of {@code POST /internal/tracking-results}.
 *
 * <p>Fields that are absent in the JSON arrive as null (arrays as {@code []} by convention).
 * {@code rawPayload} is arbitrary JSON and is capped at 256 KB by the API.
 * Source: 接口文档_终稿.md §5.23 / §6</p>
 */
public record TrackingResultReport(
        @JsonProperty("run_id") String runId,
        @JsonProperty("container_id") Long containerId,
        @JsonProperty("container_no") String containerNo,
        @JsonProperty("carrier") String carrier,
        @JsonProperty("claim_token") String claimToken,
        @JsonProperty("retry_count") Integer retryCount,
        @JsonProperty("mapping_version") Integer mappingVersion,
        @JsonProperty("queried_at") OffsetDateTime queriedAt,
        @JsonProperty("success") Boolean success,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("error_message") String errorMessage,
        @JsonProperty("raw_status") String rawStatus,
        @JsonProperty("transport_status") TransportStatus transportStatus,
        @JsonProperty("etas") List<Eta> etas,
        @JsonProperty("source_events") List<SourceEvent> sourceEvents,
        @JsonProperty("location") String location,
        @JsonProperty("vessel") String vessel,
        @JsonProperty("voyage") String voyage,
        @JsonProperty("pol") String pol,
        @JsonProperty("pod") String pod,
        @JsonProperty("final_destination") String finalDestination,
        @JsonProperty("source") String source,
        @JsonProperty("raw_payload") Object rawPayload
) {
}
