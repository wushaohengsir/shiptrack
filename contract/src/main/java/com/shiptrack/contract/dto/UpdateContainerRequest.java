package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.enums.TrackedMilestone;

/**
 * Partial update body of {@code PATCH /containers/{id}}; null fields are left unchanged.
 * Changing {@code trackedMilestone} resets the baseline.
 *
 * <p>Source: 接口文档_终稿.md §5.7</p>
 */
public record UpdateContainerRequest(
        @JsonProperty("tracked_milestone") TrackedMilestone trackedMilestone,
        @JsonProperty("container_type") String containerType,
        @JsonProperty("continue_after_pickup") Boolean continueAfterPickup,
        @JsonProperty("is_active") Boolean active
) {
}
