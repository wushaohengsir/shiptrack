package com.shiptrack.api.repo;

import com.shiptrack.contract.dto.ContainerDto;
import com.shiptrack.contract.dto.Eta;
import com.shiptrack.contract.dto.UpdateContainerResponse;
import com.shiptrack.contract.enums.EtaPrecision;
import com.shiptrack.contract.enums.EtaRisk;
import com.shiptrack.contract.enums.TrackedMilestone;
import com.shiptrack.contract.enums.TrackingHealth;
import com.shiptrack.contract.enums.TransportStatus;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;

/**
 * One row of {@code containers}. The mutable fields double as the carrier for
 * the success/failure updates so the SQL stays in one place.
 */
public record ContainerRecord(
        long id,
        long orderId,
        String containerNo,
        String carrier,
        String containerType,
        boolean checksumValid,
        TrackedMilestone trackedMilestone,
        Long baselineSnapshotId,
        TransportStatus transportStatus,
        EtaRisk etaRisk,
        TrackingHealth trackingHealth,
        String trackedEtaRawValue,
        LocalDate trackedEtaDate,
        LocalTime trackedEtaTime,
        String trackedEtaTimezone,
        EtaPrecision trackedEtaPrecision,
        OffsetDateTime trackedEtaUtcAt,
        String pol,
        String pod,
        String finalDestination,
        String location,
        String vessel,
        String voyage,
        boolean continueAfterPickup,
        OffsetDateTime trackingStartedAt,
        OffsetDateTime lastAttemptAt,
        OffsetDateTime lastSuccessAt,
        boolean active,
        String errorCode,
        String lockRunId,
        OffsetDateTime lockAcquiredAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public Eta trackedEta() {
        if (trackedEtaDate == null && trackedEtaTime == null && trackedEtaPrecision == null) {
            return null;
        }
        return new Eta(
                trackedMilestone,
                trackedEtaRawValue,
                trackedEtaDate,
                trackedEtaTime,
                trackedEtaTimezone,
                trackedEtaPrecision == null ? EtaPrecision.DATE : trackedEtaPrecision,
                trackedEtaUtcAt == null ? null : trackedEtaUtcAt.toInstant());
    }

    public ContainerDto toDto() {
        return new ContainerDto(
                id, orderId, containerNo, carrier, containerType, checksumValid,
                trackedMilestone, baselineSnapshotId, transportStatus, etaRisk, trackingHealth,
                trackedEta(), pol, pod, finalDestination, location, vessel, voyage,
                continueAfterPickup, trackingStartedAt, lastAttemptAt, lastSuccessAt,
                active, errorCode, createdAt, updatedAt);
    }

    public UpdateContainerResponse toUpdateResponse(boolean baselineReset) {
        return new UpdateContainerResponse(
                id, orderId, containerNo, carrier, containerType, checksumValid,
                trackedMilestone, baselineSnapshotId, baselineReset,
                transportStatus, etaRisk, trackingHealth, trackedEta(),
                pol, pod, finalDestination, location, vessel, voyage,
                continueAfterPickup, trackingStartedAt, lastAttemptAt, lastSuccessAt,
                active, errorCode, createdAt, updatedAt);
    }
}
