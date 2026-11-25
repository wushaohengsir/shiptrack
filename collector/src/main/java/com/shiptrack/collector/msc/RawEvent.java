package com.shiptrack.collector.msc;

import com.shiptrack.contract.enums.EventTimeType;

import java.time.OffsetDateTime;

/**
 * One timeline row. The milestone is resolved by the status normaliser, not
 * here; the timestamp and time semantics are kept only when the page stated
 * them explicitly.
 */
public record RawEvent(
        String rawText,
        EventTimeType eventTimeType,
        OffsetDateTime occurredAt,
        String timezone
) {

    public RawEvent(String rawText) {
        this(rawText, null, null, null);
    }
}
