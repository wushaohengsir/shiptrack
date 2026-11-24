package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.enums.EtaRisk;

import java.time.OffsetDateTime;

/**
 * Order object returned by the public API.
 *
 * <p>Source: 接口文档_终稿.md §3.1</p>
 */
public record OrderDto(
        @JsonProperty("id") long id,
        @JsonProperty("order_no") String orderNo,
        @JsonProperty("title") String title,
        @JsonProperty("container_count") int containerCount,
        @JsonProperty("summary") OrderSummary summary,
        @JsonProperty("eta_range") EtaRange etaRange,
        @JsonProperty("highest_risk") EtaRisk highestRisk,
        @JsonProperty("archived_at") OffsetDateTime archivedAt,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt
) {
}
