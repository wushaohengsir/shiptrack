package com.shiptrack.collector.mapping;

import com.shiptrack.contract.enums.TransportStatus;

/**
 * One read-only row of {@code status-mappings.yaml}.
 */
public record StatusMapping(String carrier, String match, TransportStatus transportStatus, int priority) {
}
