package com.shiptrack.contract.internal;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Response of {@code GET /internal/tracking-runs}.
 *
 * <p>Source: 接口文档_终稿.md §5.24</p>
 */
public record TrackingRunsResponse(
        @JsonProperty("runs") List<TrackingRunDto> runs
) {
}
