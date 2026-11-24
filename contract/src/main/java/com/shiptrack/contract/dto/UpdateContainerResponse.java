package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.enums.EtaRisk;
import com.shiptrack.contract.enums.TrackedMilestone;
import com.shiptrack.contract.enums.TrackingHealth;
import com.shiptrack.contract.enums.TransportStatus;

import java.time.OffsetDateTime;

/**
 * Response of {@code PATCH /containers/{id}}: the updated container object plus
 * {@code baseline_reset}, which is true when {@code tracked_milestone} changed and
 * the baseline was cleared for rebuild.
 *
 * <p>Source: 接口文档_终稿.md §5.7</p>
 */
public record UpdateContainerResponse(
        @JsonProperty("id") long id,
        @JsonProperty("order_id") long orderId,
        @JsonProperty("container_no") String containerNo,
        @JsonProperty("carrier") String carrier,
        @JsonProperty("container_type") String containerType,
        @JsonProperty("checksum_valid") boolean checksumValid,
        @JsonProperty("tracked_milestone") TrackedMilestone trackedMilestone,
        @JsonProperty("baseline_snapshot_id") Long baselineSnapshotId,
        @JsonProperty("baseline_reset") boolean baselineReset,
        @JsonProperty("transport_status") TransportStatus transportStatus,
        @JsonProperty("eta_risk") EtaRisk etaRisk,
        @JsonProperty("tracking_health") TrackingHealth trackingHealth,
        @JsonProperty("tracked_eta") Eta trackedEta,
        @JsonProperty("pol") String pol,
        @JsonProperty("pod") String pod,
        @JsonProperty("final_destination") String finalDestination,
        @JsonProperty("location") String location,
        @JsonProperty("vessel") String vessel,
        @JsonProperty("voyage") String voyage,
        @JsonProperty("continue_after_pickup") boolean continueAfterPickup,
        @JsonProperty("tracking_started_at") OffsetDateTime trackingStartedAt,
        @JsonProperty("last_attempt_at") OffsetDateTime lastAttemptAt,
        @JsonProperty("last_success_at") OffsetDateTime lastSuccessAt,
        @JsonProperty("is_active") boolean active,
        @JsonProperty("error_code") String errorCode,
        @JsonProperty("created_at") OffsetDateTime createdAt,
        @JsonProperty("updated_at") OffsetDateTime updatedAt
) {
}
