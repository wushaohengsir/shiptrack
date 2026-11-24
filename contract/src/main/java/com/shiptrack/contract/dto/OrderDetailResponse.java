package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.enums.EtaRisk;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Full order object plus its containers, returned by {@code POST /orders} and
 * {@code GET /orders/{id}}.
 *
 * <p>Same fields as {@link OrderDto} (§3.1) with the {@code containers[]} array
 * required by §5.1 / §5.3. Source: 接口文档_终稿.md §5.1 / §5.3</p>
 */
public record OrderDetailResponse(
        @JsonProperty("id") long id,
        @JsonProperty("order_no") String orderNo,
        @JsonProperty("title") String title,
        @JsonProperty("container_count") int containerCount,
        @JsonProperty("summary") OrderSummary summary,
        @JsonProperty("eta_range") EtaRange etaRange,
        @JsonProperty("highest_risk") EtaRisk highestRisk,
        @JsonProperty("archived_at") OffsetDateTime archivedAt,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        @JsonProperty("containers") List<ContainerDto> containers
) {
}
