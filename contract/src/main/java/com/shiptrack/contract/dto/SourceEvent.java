package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.enums.EventTimeType;
import com.shiptrack.contract.enums.TransportStatus;

import java.time.OffsetDateTime;

/**
 * One structured trajectory node from the carrier page.
 *
 * <p>Source: 接口文档_终稿.md §3.4</p>
 */
public record SourceEvent(
        @JsonProperty("milestone") TransportStatus milestone,
        @JsonProperty("event_time_type") EventTimeType eventTimeType,
        @JsonProperty("occurred_at") OffsetDateTime occurredAt,
        @JsonProperty("timezone") String timezone,
        @JsonProperty("location") String location,
        @JsonProperty("vessel") String vessel,
        @JsonProperty("voyage") String voyage,
        @JsonProperty("raw_text") String rawText
) {
}
