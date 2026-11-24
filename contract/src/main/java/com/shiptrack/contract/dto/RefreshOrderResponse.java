package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.enums.AttemptStatus;

/**
 * Response of {@code POST /orders/{id}/refresh}.
 *
 * <p>Source: 接口文档_终稿.md §5.10</p>
 */
public record RefreshOrderResponse(
        @JsonProperty("run_id") String runId,
        @JsonProperty("order_id") long orderId,
        @JsonProperty("status") AttemptStatus status,
        @JsonProperty("container_count") int containerCount
) {
}
