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

    /**
     * Wording that is evidence the consignee took the full container. Only
     * these phrases may reach {@code PICKED_UP}; loading, discharging,
     * transshipment and empty-container movements are deliberately absent.
     */
    private static final Pattern PICKUP_EVIDENCE = Pattern.compile(
            "(?i)(?:\\b(?:container|full\\s+container|import\\s+full|full\\s+import)\\b"
                    + "[\\s\\S]{0,30}?\\bpicked\\s*up\\b)"
                    + "|(?:\\bpicked\\s*up\\s+by\\s+(?:the\\s+)?(?:consignee|receiver|customer)\\b)"
                    + "|(?:\\b(?:import\\s+full|full)\\s+gate\\s*out\\b)");

    /** An empty box being picked up is a different milestone. */
    private static final Pattern EMPTY_PICKUP = Pattern.compile(
            "(?i)\\bempty\\b[\\s\\S]{0,30}?\\bpicked?\\s*up\\b|\\bempty\\s+pick\\s*up\\b");

    private StatusWording() {
    }

    /**
     * True only for explicit full-container pickup or gate-out evidence. The
     * reporter uses this as a second line of defence: no mapping change may
     * turn a loading/discharge/transshipment phrase into the terminal
     * {@code PICKED_UP} status.
     */
    public static boolean isPickupEvidence(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (EMPTY_PICKUP.matcher(text).find()) {
            return false;
        }
        return PICKUP_EVIDENCE.matcher(text).find();
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
