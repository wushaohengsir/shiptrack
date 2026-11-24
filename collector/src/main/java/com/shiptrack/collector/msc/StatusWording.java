package com.shiptrack.collector.msc;

import com.shiptrack.contract.enums.EventTimeType;

import java.util.regex.Pattern;

/**
 * The one place that decides whether a carrier phrase describes a plan, a
 * cancellation or something that actually happened.
 *
 * <p>Both the timeline ({@code SourceEvent.event_time_type}) and the top-level
 * transport status use these checks. Keeping them together is what stops
 * "Container picked up cancelled" from being mapped to the terminal
 * {@code PICKED_UP} status while its timeline row is correctly cancelled
 * (AC-29).</p>
 */
public final class StatusWording {

    private static final Pattern CANCELLED = Pattern.compile(
            "(?i)\\b(cancel(?:led|ed|lation|ation)?|void(?:ed)?)\\b");

    private static final Pattern PLANNED = Pattern.compile(
            "(?i)\\b(estimated|expected|planned|scheduled|eta)\\b");

    private static final Pattern ACTUAL = Pattern.compile(
            "(?i)\\b(actual|gate in|loaded|departed|discharged|arrived|available|"
                    + "picked up|returned|released|transshipment)\\b");

    private StatusWording() {
    }

    public static boolean isCancelled(String text) {
        return text != null && CANCELLED.matcher(text).find();
    }

    public static boolean isPlanned(String text) {
        return text != null && PLANNED.matcher(text).find();
    }

    /**
     * Semantics of a timeline row, or {@code null} when the page does not say.
     * Cancellation wins over plan/actual wording because it overrides them.
     */
    public static EventTimeType eventTimeType(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (isCancelled(text)) {
            return EventTimeType.CANCELLED;
        }
        if (isPlanned(text)) {
            return EventTimeType.PLANNED;
        }
        if (ACTUAL.matcher(text).find()) {
            return EventTimeType.ACTUAL;
        }
        return null;
    }
}
