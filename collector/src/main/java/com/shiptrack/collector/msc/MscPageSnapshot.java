package com.shiptrack.collector.msc;

import java.util.List;

/**
 * Raw, untyped view of one loaded tracking page. Capture is browser-bound;
 * parsing this record is pure and unit testable.
 *
 * <p>{@code resultBlockText} is the text of the <em>visible result block that
 * this query produced</em>, not the whole page. The parser reads the container
 * number, status and timeline only from that block, so a stale or unrelated
 * result elsewhere on the page can never be reported as this container's
 * status (AC-29). It is {@code null} when no result block is visible.</p>
 *
 * <p>{@code resultAmbiguous} is set when the visible block itself carries more
 * than one conflicting value for a field (for example two visible status
 * labels with different text). Nothing is published from an ambiguous block.</p>
 */
public record MscPageSnapshot(
        String title,
        String url,
        String bodyText,
        String statusText,
        List<String> eventLines,
        boolean passwordFieldPresent,
        String resultBlockText,
        boolean resultAmbiguous
) {

    public MscPageSnapshot {
        eventLines = eventLines == null ? List.of() : List.copyOf(eventLines);
    }

    public MscPageSnapshot(String title,
                           String url,
                           String bodyText,
                           String statusText,
                           List<String> eventLines,
                           boolean passwordFieldPresent,
                           String resultBlockText) {
        this(title, url, bodyText, statusText, eventLines, passwordFieldPresent, resultBlockText, false);
    }

    /** Snapshot without a scoped result block (classifiers and legacy callers). */
    public MscPageSnapshot(String title,
                           String url,
                           String bodyText,
                           String statusText,
                           List<String> eventLines,
                           boolean passwordFieldPresent) {
        this(title, url, bodyText, statusText, eventLines, passwordFieldPresent, null, false);
    }
}
