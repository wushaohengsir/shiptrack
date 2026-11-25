package com.shiptrack.collector.msc;

import java.util.List;

/**
 * Page content after structural parsing. Status text is kept raw; converting it
 * to {@code TransportStatus} is the mapping engine's job.
 */
public record ParsedTracking(
        String rawStatus,
        String location,
        String vessel,
        String voyage,
        String pol,
        String pod,
        String finalDestination,
        List<RawEta> etas,
        List<RawEvent> events
) {

    public ParsedTracking {
        etas = etas == null ? List.of() : List.copyOf(etas);
        events = events == null ? List.of() : List.copyOf(events);
    }
}
