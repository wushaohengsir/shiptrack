package com.shiptrack.api.repo;

import java.time.OffsetDateTime;

public record OrderRecord(
        long id,
        String orderNo,
        String title,
        OffsetDateTime archivedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
