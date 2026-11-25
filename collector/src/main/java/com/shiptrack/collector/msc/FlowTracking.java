package com.shiptrack.collector.msc;

import com.shiptrack.contract.enums.EventTimeType;
import com.shiptrack.contract.enums.TrackedMilestone;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads the calibrated live result card:
 * {@code msc-flow-tracking__container} summary cells and the
 * {@code msc-flow-tracking__step} event table.
 *
 * <p>The card has no {@code Status:} label. The top-level status is therefore
 * derived from the newest event that actually happened (the {@code PLANNED}
 * arrival row is not a state), and only explicit full-container pickup or
 * gate-out wording may become {@code PICKED_UP}; loading, discharge,
 * transshipment and empty-box rows are reported verbatim and map to their own
 * milestones. When no event can be classified, the generic in-transit wording
 * is used and the latest move is kept as the location.</p>
 *
 * <p>The summary itself and each event row are plain text captured by the
 * probe, so this class is pure and testable against the saved fixture.</p>
 */
final class FlowTracking {

    static final String IN_TRANSIT = "In transit";

    private static final Pattern ARRIVAL = Pattern.compile(
            "(?i)\\b(estimated\\s+time\\s+of\\s+arrival|expected\\s+arrival|"
                    + "actual\\s+arrival|arrival|arrive[ds]?|eta)\\b");
    private static final Pattern EXPORT_LOAD = Pattern.compile(
            "(?i)\\bexport\\b[\\s\\S]*\\bloaded\\s+on\\s+vessel\\b|\\bloaded\\s+on\\s+vessel\\b");
    private static final Pattern EXPORT = Pattern.compile("(?i)^\\s*export\\b");
    private static final Pattern TRANSSHIPMENT = Pattern.compile(
            "(?i)\\btrans-?ship(?:ment|ped|ping)?\\b");

    private final Map<String, String> summary;
    private final List<FlowEvent> events;
    private final List<RawEta> etas;
    private final String derivedStatus;

    private FlowTracking(Map<String, String> summary,
                         List<FlowEvent> events,
                         List<RawEta> etas,
                         String derivedStatus) {
        this.summary = summary;
        this.events = List.copyOf(events);
        this.etas = List.copyOf(etas);
        this.derivedStatus = derivedStatus;
    }

    static Optional<FlowTracking> from(Map<String, String> rawSummary,
                                       List<List<String>> rawRows,
                                       EtaTextParser.DateOrder order) {
        Map<String, String> summary = normalizeSummary(rawSummary);
        List<FlowEvent> events = new ArrayList<>();
        if (rawRows != null) {
            for (List<String> row : rawRows) {
                FlowEvent event = toEvent(row, order);
                if (event != null) {
                    events.add(event);
                }
            }
        }
        if (summary.isEmpty() && events.isEmpty()) {
            return Optional.empty();
        }

        String pod = firstLocation(events, ARRIVAL)
                .orElseGet(() -> summary.get("pod"));
        String pol = firstLocation(events, EXPORT_LOAD)
                .orElseGet(() -> firstLocation(events, EXPORT).orElse(summary.get("pol")));
        String transshipment = events.stream()
                .filter(event -> event.location() != null
                        && TRANSSHIPMENT.matcher(event.description()).find())
                .map(FlowEvent::location)
                .filter(location -> !samePlace(location, pod))
                .findFirst()
                .orElse(null);
        String finalDestination = firstSummary(summary,
                "shipped to", "final destination", "place of delivery");

        List<RawEta> etas = buildEtas(summary, events, pod, order);
        return Optional.of(new FlowTracking(
                summary, events, etas, deriveStatus(events)));
    }

    Map<String, String> summary() {
        return summary;
    }

    List<FlowEvent> events() {
        return events;
    }

    List<RawEta> etas() {
        return etas;
    }

    String derivedStatus() {
        return derivedStatus;
    }

    String container() {
        return summary.get("container");
    }

    String containerType() {
        return summary.get("type");
    }

    String latestMove() {
        return summary.get("latest move");
    }

    String pol() {
        return firstLocation(events, EXPORT_LOAD)
                .orElseGet(() -> firstLocation(events, EXPORT).orElse(summary.get("pol")));
    }

    String pod() {
        return firstLocation(events, ARRIVAL).orElse(summary.get("pod"));
    }

    String transshipmentPort() {
        return events.stream()
                .filter(event -> event.location() != null
                        && TRANSSHIPMENT.matcher(event.description()).find())
                .map(FlowEvent::location)
                .filter(location -> !samePlace(location, pod()))
                .findFirst()
                .orElse(null);
    }

    String finalDestination() {
        return firstSummary(summary, "shipped to", "final destination", "place of delivery");
    }

    /**
     * The newest row that is not a plan or a cancellation. Rows are printed
     * newest-first, so the first one wins on an equal or unreadable date.
     */
    private static String deriveStatus(List<FlowEvent> events) {
        FlowEvent latest = null;
        for (FlowEvent event : events) {
            if (event.location() == null || event.description() == null
                    || event.description().isBlank()) {
                continue;
            }
            if (event.eventTimeType() == EventTimeType.PLANNED
                    || event.eventTimeType() == EventTimeType.CANCELLED) {
                continue;
            }
            if (latest == null || isLater(event.date(), latest.date())) {
                latest = event;
            }
        }
        return latest == null ? IN_TRANSIT : latest.description();
    }

    private static boolean isLater(LocalDate candidate, LocalDate current) {
        if (candidate == null) {
            return false;
        }
        return current == null || candidate.isAfter(current);
    }

    private static List<RawEta> buildEtas(Map<String, String> summary,
                                          List<FlowEvent> events,
                                          String pod,
                                          EtaTextParser.DateOrder order) {
        List<RawEta> etas = new ArrayList<>();
        String podEta = summary.get("pod eta");
        if (podEta != null) {
            Optional<LocalDate> date = EtaTextParser.parseDate(podEta, order);
            if (date.isPresent()) {
                Optional<EtaTextParser.ZonedTime> time = EtaTextParser.parseTime(podEta);
                etas.add(new RawEta(
                        TrackedMilestone.POD_ARRIVAL,
                        "POD ETA " + podEta,
                        date.get(),
                        time.map(EtaTextParser.ZonedTime::time).orElse(null),
                        time.map(EtaTextParser.ZonedTime::timezone).orElse(null)));
            }
        }
        for (FlowEvent event : events) {
            if (event.date() == null || !ARRIVAL.matcher(event.description()).find()) {
                continue;
            }
            // An arrival at a transshipment port is not the monitored POD.
            if (pod != null && event.location() != null && !samePlace(event.location(), pod)) {
                continue;
            }
            Optional<EtaTextParser.ZonedTime> time = EtaTextParser.parseTime(event.rawText());
            etas.add(new RawEta(
                    TrackedMilestone.POD_ARRIVAL,
                    event.rawText(),
                    event.date(),
                    time.map(EtaTextParser.ZonedTime::time).orElse(null),
                    time.map(EtaTextParser.ZonedTime::timezone).orElse(null)));
        }
        return dedupe(etas);
    }

    /** Identical values from the summary and the timeline are one ETA. */
    private static List<RawEta> dedupe(List<RawEta> etas) {
        Map<String, RawEta> unique = new LinkedHashMap<>();
        for (RawEta eta : etas) {
            String key = eta.milestone() + "|" + eta.date() + "|" + eta.time() + "|" + eta.timezone();
            unique.putIfAbsent(key, eta);
        }
        return new ArrayList<>(unique.values());
    }

    private static Optional<String> firstLocation(List<FlowEvent> events, Pattern pattern) {
        for (FlowEvent event : events) {
            if (event.location() != null && pattern.matcher(event.description()).find()) {
                return Optional.of(event.location());
            }
        }
        return Optional.empty();
    }

    private static String firstSummary(Map<String, String> summary, String... keys) {
        for (String key : keys) {
            String value = summary.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Map<String, String> normalizeSummary(Map<String, String> raw) {
        Map<String, String> summary = new LinkedHashMap<>();
        if (raw == null) {
            return summary;
        }
        raw.forEach((heading, value) -> {
            String key = clean(heading);
            String text = clean(value);
            if (key != null && text != null) {
                summary.putIfAbsent(key.toLowerCase(Locale.ROOT), text);
            }
        });
        return summary;
    }

    private static FlowEvent toEvent(List<String> row, EtaTextParser.DateOrder order) {
        if (row == null) {
            return null;
        }
        List<String> cells = new ArrayList<>();
        for (String cell : row) {
            String clean = clean(cell);
            if (clean != null) {
                cells.add(clean);
            }
        }
        if (cells.isEmpty()) {
            return null;
        }
        String rawText = String.join(" | ", cells);
        if (cells.size() < 3) {
            // Old single-line rows still make it into the timeline, but they
            // carry no structured columns and therefore cannot drive the
            // derived status or the ports. Their timestamp is still parsed:
            // dropping it would lose a real event time the reporter publishes.
            String text = cells.getFirst();
            Optional<EtaTextParser.ZonedTime> time = EtaTextParser.parseTime(text);
            return new FlowEvent(
                    null,
                    null, text, null, null, rawText,
                    StatusWording.eventTimeType(text),
                    EtaTextParser.parseDateTime(text, order).orElse(null),
                    time.map(EtaTextParser.ZonedTime::timezone).orElse(null));
        }
        LocalDate date = EtaTextParser.parseDate(cells.get(0), order).orElse(null);
        String location = cells.get(1);
        String description = cells.get(2);
        String vesselVoyage = cells.size() > 3 ? cells.get(3) : null;
        String facility = cells.size() > 4 ? cells.get(4) : null;
        Optional<EtaTextParser.ZonedTime> time = EtaTextParser.parseTime(rawText);
        return new FlowEvent(
                date,
                location,
                description,
                vesselVoyage,
                facility,
                rawText,
                StatusWording.eventTimeType(description),
                EtaTextParser.parseDateTime(rawText, order).orElse(null),
                time.map(EtaTextParser.ZonedTime::timezone).orElse(null));
    }

    private static boolean samePlace(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return city(left).equalsIgnoreCase(city(right));
    }

    private static String city(String place) {
        return place.split(",")[0].trim();
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    record FlowEvent(
            LocalDate date,
            String location,
            String description,
            String vesselVoyage,
            String facility,
            String rawText,
            EventTimeType eventTimeType,
            OffsetDateTime occurredAt,
            String timezone
    ) {
        FlowEvent {
            Objects.requireNonNull(rawText, "rawText");
        }
    }
}
