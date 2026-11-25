package com.shiptrack.api.repo;

import com.shiptrack.contract.enums.AttemptStatus;
import com.shiptrack.contract.enums.RunTrigger;
import com.shiptrack.contract.enums.TransportStatus;

import java.time.OffsetDateTime;

/**
 * One row of {@code tracking_snapshots}; JSON columns stay raw so the caller
 * can decide whether to parse them.
 */
public record SnapshotRecord(
        long id,
        long containerId,
        String runId,
        RunTrigger runTrigger,
        AttemptStatus attemptStatus,
        int retryCount,
        OffsetDateTime queuedAt,
        OffsetDateTime claimedAt,
        String claimToken,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String source,
        Boolean success,
        TransportStatus transportStatus,
        String rawStatus,
        String etasJson,
        String sourceEventsJson,
        String location,
        String vessel,
        String voyage,
        String pol,
        String pod,
        String finalDestination,
        String contentHash,
        Integer mappingVersion,
        String errorCode,
        String errorMessage
) {
}
