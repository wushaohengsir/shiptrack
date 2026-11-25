package com.shiptrack.api.repo;

import com.shiptrack.contract.enums.EtaRisk;
import com.shiptrack.contract.enums.EventTimeType;
import com.shiptrack.contract.enums.EventType;

import java.time.OffsetDateTime;

public record EventRecord(
        long id,
        long containerId,
        Long notificationId,
        String eventKey,
        EventType eventType,
        String fieldName,
        EventTimeType eventTimeType,
        Long fromSnapshotId,
        Long toSnapshotId,
        Long baselineSnapshotId,
        String baselineDeltaJson,
        EtaRisk riskBefore,
        EtaRisk riskAfter,
        String oldValueJson,
        String newValueJson,
        OffsetDateTime sourceEventAt,
        OffsetDateTime detectedAt,
        String source
) {
}
