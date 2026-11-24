package com.shiptrack.contract.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.enums.AttemptStatus;

import java.util.List;

/**
 * Response of {@code POST /internal/tracking-results}.
 *
 * <p>Source: 接口文档_终稿.md §5.23</p>
 */
public record TrackingResultResponse(
        @JsonProperty("snapshot_id") long snapshotId,
        @JsonProperty("attempt_status") AttemptStatus attemptStatus,
        @JsonProperty("events") List<EventRef> events,
        @JsonProperty("order_notification") OrderNotificationResult orderNotification
) {
}
