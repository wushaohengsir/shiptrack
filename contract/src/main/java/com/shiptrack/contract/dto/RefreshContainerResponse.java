package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.enums.AttemptStatus;

/**
 * Response of {@code POST /containers/{id}/refresh}.
 *
 * <p>Source: 接口文档_终稿.md §5.9</p>
 */
public record RefreshContainerResponse(
        @JsonProperty("run_id") String runId,
        @JsonProperty("container_id") long containerId,
        @JsonProperty("status") AttemptStatus status
) {
}
