package com.shiptrack.collector.msc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        boolean resultAmbiguous,
        Map<String, String> summaryFacts,
        List<List<String>> eventRows
) {

    public MscPageSnapshot {
        eventLines = eventLines == null ? List.of() : List.copyOf(eventLines);
        summaryFacts = summaryFacts == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(summaryFacts));
        eventRows = eventRows == null ? List.of() : immutableRows(eventRows);
    }

    public MscPageSnapshot(String title,
                           String url,
                           String bodyText,
                           String statusText,
                           List<String> eventLines,
                           boolean passwordFieldPresent,
                           String resultBlockText,
                           boolean resultAmbiguous) {
        this(title, url, bodyText, statusText, eventLines, passwordFieldPresent,
                resultBlockText, resultAmbiguous, Map.of(), List.of());
    }

    public MscPageSnapshot(String title,
                           String url,
                           String bodyText,
                           String statusText,
                           List<String> eventLines,
                           boolean passwordFieldPresent,
                           String resultBlockText) {
        this(title, url, bodyText, statusText, eventLines, passwordFieldPresent,
                resultBlockText, false, Map.of(), List.of());
    }

    /** Snapshot without a scoped result block (classifiers and legacy callers). */
    public MscPageSnapshot(String title,
                           String url,
                           String bodyText,
                           String statusText,
                           List<String> eventLines,
                           boolean passwordFieldPresent) {
        this(title, url, bodyText, statusText, eventLines, passwordFieldPresent,
                null, false, Map.of(), List.of());
    }

    private static List<List<String>> immutableRows(List<List<String>> rows) {
        List<List<String>> copy = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            copy.add(row == null ? List.of() : List.copyOf(row));
        }
        return List.copyOf(copy);
    }
}
