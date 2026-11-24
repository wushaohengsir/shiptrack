package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.enums.NotCreatedReason;

/**
 * Returned by refresh / run endpoints when no tracking batch is created, e.g.
 * {@code {"created": false, "reason": "NO_ACTIVE_CONTAINERS"}}.
 *
 * <p>Source: 接口文档_终稿.md §5.21</p>
 */
public record RunNotCreatedResponse(
        @JsonProperty("created") boolean created,
        @JsonProperty("reason") NotCreatedReason reason
) {
}
