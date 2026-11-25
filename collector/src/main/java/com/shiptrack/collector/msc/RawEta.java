package com.shiptrack.collector.msc;

import com.shiptrack.contract.enums.TrackedMilestone;

import java.time.LocalDate;
import java.time.LocalTime;

/**
 * One ETA as it appeared on the page, before it becomes a contract {@code Eta}.
 * {@code time}/{@code timezone} are null unless the page printed both, because
 * a guessed timezone would silently corrupt the UTC conversion.
 */
public record RawEta(
        TrackedMilestone milestone,
        String rawValue,
        LocalDate date,
        LocalTime time,
        String timezone
) {
}
