package com.shiptrack.collector.msc;

import java.util.List;

/**
 * Raw, untyped view of one loaded tracking page. Capture is browser-bound;
 * parsing this record is pure and unit testable.
 */
public record MscPageSnapshot(
        String title,
        String url,
        String bodyText,
        String statusText,
        List<String> eventLines,
        boolean passwordFieldPresent
) {

    public MscPageSnapshot {
        eventLines = eventLines == null ? List.of() : List.copyOf(eventLines);
    }
}
