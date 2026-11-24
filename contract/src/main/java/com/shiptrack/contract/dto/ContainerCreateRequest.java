package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.enums.TrackedMilestone;

/**
 * One container in an order-create or add-container request.
 *
 * <p>Source: 接口文档_终稿.md §5.1 / §5.6</p>
 */
public record ContainerCreateRequest(
        @JsonProperty("container_no") String containerNo,
        @JsonProperty("carrier") String carrier,
        @JsonProperty("container_type") String containerType,
        @JsonProperty("tracked_milestone") TrackedMilestone trackedMilestone,
        @JsonProperty("continue_after_pickup") Boolean continueAfterPickup
) {
}
