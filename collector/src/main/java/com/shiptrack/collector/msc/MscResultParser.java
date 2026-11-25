package com.shiptrack.collector.msc;

import com.shiptrack.contract.enums.TrackedMilestone;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Structural parser for a captured tracking page. It only reports what the page
 * actually printed: absent fields stay null, unreadable ETAs are dropped, and a
 * page without a status raises {@link MscParseException}.
 *
 * <p>Selector/probe extraction feeds this class, so the rules can be tested
 * against saved page text without a browser.</p>
 */
@Component
public final class MscResultParser {

    private static final Pattern STATUS_LINE =
            Pattern.compile("(?im)^\\s*(?:current\\s+)?(?:shipment\\s+)?status\\s*[:\\-]\\s*(.+)$");
    private static final Pattern VESSEL =
            Pattern.compile("(?im)^\\s*vessel(?:\\s+name)?\\s*[:\\-]\\s*(.+)$");
    private static final Pattern VOYAGE =
            Pattern.compile("(?im)^\\s*voyage(?:\\s+no\\.?)?\\s*[:\\-]\\s*(.+)$");
    private static final Pattern POL =
            Pattern.compile("(?im)^\\s*(?:port\\s+of\\s+loading|pol|origin)\\s*[:\\-]\\s*(.+)$");
    private static final Pattern POD =
            Pattern.compile("(?im)^\\s*(?:port\\s+of\\s+discharge|pod|destination)\\s*[:\\-]\\s*(.+)$");
    private static final Pattern FINAL_DESTINATION =
            Pattern.compile("(?im)^\\s*final\\s+destination\\s*[:\\-]\\s*(.+)$");
    private static final Pattern LOCATION =
            Pattern.compile("(?im)^\\s*(?:current\\s+location|last\\s+known\\s+location|location)\\s*[:\\-]\\s*(.+)$");

    /**
     * Only explicit forecast wording marks a value as an ETA candidate. "Actual
     * arrival at Vancouver" is a fact, not an estimate, and must not be
     * published as the POD ETA.
     */
    private static final Pattern ETA_HINT = Pattern.compile(
            "(?i)\\b(eta|estimated|expected|planned|scheduled)\\b");

    /**
     * A date is only an arrival estimate when the line says so. "Scheduled
     * loading at Vancouver" or "Scheduled return at Vancouver" carry forecast
     * wording and the POD name, but they describe operations that are not the
     * arrival this task tracks; publishing them would end tracking on the
     * wrong milestone.
     */
    private static final Pattern ARRIVAL_WORDING = Pattern.compile(
            "(?i)\\b(eta|arrival|arrivals|arrive|arrives|arriving|"
                    + "expected\\s+arrival|estimated\\s+arrival)\\b");

    /**
     * Operations that print a date next to a port but are not the arrival.
     * "Discharge" is deliberately absent: it is also the name of the monitored
     * node ("Port of Discharge"), and an actual or planned discharge line
     * without arrival wording is already dropped by the checks below.
     */
    private static final Pattern NON_ARRIVAL_OPERATION = Pattern.compile(
            "(?i)\\b(loading|load|loaded|unloading|unload|"
                    + "return(?:ed|ing|s)?|pickup|pick\\s+up|delivery|deliver(?:ed|y)?|"
                    + "available|stow(?:ed|ing)?)\\b");

    /**
     * A departure is not an arrival. "Scheduled departure from Vancouver"
     * carries forecast wording and a date, so it would otherwise be reported as
     * the POD arrival.
     */
    private static final Pattern DEPARTURE_WORDING = Pattern.compile(
            "(?i)\\b(departure|departs?|departed|sailing|sails?|etd|gate\\s*out)\\b");

    /**
     * Actual events are facts. When one line mixes an actual time with an
     * estimate, the date cannot be bound to the estimate with confidence, so
     * the line is dropped instead of guessing.
     */
    private static final Pattern ACTUAL_TIME_WORDING = Pattern.compile(
            "(?i)\\b(actual|arrived|discharged|gate\\s*in|gate\\s*out|picked\\s+up|returned)\\b");

    private static final Pattern CONTAINER_TOKEN = Pattern.compile(
            "(?i)\\b([A-Z]{4}\\d{7})\\b");

    /**
     * A rendered row can print several fields side by side. Each field is a
     * separate statement: the port in one field does not qualify the date in
     * another, and a date in the discharge field is not the POD arrival.
     */
    private static final Pattern FIELD_SEPARATOR = Pattern.compile(
            "\\s*(?:[|;•]|[—–])\\s*|\\s+-\\s+");

    /**
     * "Expected arrival at Seattle" binds the date to Seattle. When the
     * monitored node is known and names another city, the field is dropped
     * instead of borrowing the row's POD label. The place capture never spans
     * a date, so the date stays outside the captured text.
     */
    private static final Pattern ARRIVAL_PLACE = Pattern.compile(
            "(?i)\\b(?:arrival|arrive[ds]?|arriving|eta|expected\\s+arrival|"
                    + "estimated\\s+(?:time\\s+of\\s+)?arrival)\\b"
                    + "[^\\d|;•—–]{0,40}?\\b(?:at|to|in)\\s+([\\p{L}][\\p{L} .'-]{1,40})");

    private static final int MAX_RAW_VALUE = 200;

    private final EtaTextParser.DateOrder dateOrder;

    /** Defaults to the MSC site's day-first numeric dates. */
    public MscResultParser() {
        this(EtaTextParser.DateOrder.DAY_FIRST);
    }

    @Autowired
    public MscResultParser(MscProperties properties) {
        this(properties.isDayFirst()
                ? EtaTextParser.DateOrder.DAY_FIRST
                : EtaTextParser.DateOrder.MONTH_FIRST);
    }

    MscResultParser(EtaTextParser.DateOrder dateOrder) {
        this.dateOrder = dateOrder == null ? EtaTextParser.DateOrder.DAY_FIRST : dateOrder;
    }

    public ParsedTracking parse(MscPageSnapshot snapshot, String containerNo) {
        // Only the result block this query produced may be read. The whole page
        // can hold another container's result (a previous search, a highlighted
        // demo row), and reporting it as this container's status is exactly the
        // premature-stop bug AC-29 forbids.
        String block = snapshot.resultBlockText();
        if (block == null || block.isBlank()) {
            throw new MscParseException("no visible result block for container " + containerNo);
        }
        if (snapshot.resultAmbiguous()) {
            throw new MscParseException("visible result block shows conflicting values for container "
                    + containerNo);
        }
        requireExactlyOneContainer(block, containerNo);

        FlowTracking flow = FlowTracking.from(
                snapshot.summaryFacts(), snapshot.eventRows(), dateOrder).orElse(null);

        List<String> statusLines = matches(STATUS_LINE, block);
        String rawStatus = snapshot.statusText();
        if (rawStatus == null || rawStatus.isBlank()) {
            rawStatus = summaryStatus(snapshot.summaryFacts());
        }
        if (rawStatus == null || rawStatus.isBlank()) {
            // Without a visible status element, the block itself must be
            // unambiguous: two different status values mean ownership of the
            // result cannot be established.
            if (distinct(statusLines).size() > 1) {
                throw new MscParseException("result block shows conflicting status values for container "
                        + containerNo);
            }
            rawStatus = statusLines.isEmpty() ? null : statusLines.getFirst();
        }
        if ((rawStatus == null || rawStatus.isBlank()) && flow != null) {
            // The live card prints no Status: field; derive it from the
            // newest event that actually happened, never from a plan.
            rawStatus = flow.derivedStatus();
        }
        if (rawStatus == null || rawStatus.isBlank()) {
            throw new MscParseException("no status text found for container " + containerNo);
        }
        rawStatus = rawStatus.trim();

        String pol = match(POL, block);
        String pod = match(POD, block);
        String finalDestination = match(FINAL_DESTINATION, block);
        String location = match(LOCATION, block);
        if (flow != null) {
            // Explicit labeled fields win; the structured card fills the gaps.
            pol = pol != null ? pol : flow.pol();
            pod = pod != null ? pod : flow.pod();
            finalDestination = finalDestination != null ? finalDestination : flow.finalDestination();
            location = location != null ? location : flow.latestMove();
        }

        List<RawEvent> events = new ArrayList<>();
        if (flow != null && !flow.events().isEmpty()) {
            for (FlowTracking.FlowEvent event : flow.events()) {
                events.add(new RawEvent(
                        event.rawText(),
                        event.eventTimeType(),
                        event.occurredAt(),
                        event.timezone()));
            }
        } else {
            for (String line : snapshot.eventLines()) {
                String trimmed = line == null ? "" : line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                Optional<EtaTextParser.ZonedTime> time = EtaTextParser.parseTime(trimmed);
                events.add(new RawEvent(
                        trimmed,
                        StatusWording.eventTimeType(trimmed),
                        EtaTextParser.parseDateTime(trimmed, dateOrder).orElse(null),
                        time.map(EtaTextParser.ZonedTime::timezone).orElse(null)));
            }
        }

        List<RawEta> structuredEtas = flow == null ? List.of() : flow.etas();
        return new ParsedTracking(
                rawStatus,
                location,
                match(VESSEL, block),
                match(VOYAGE, block),
                pol,
                pod,
                finalDestination,
                parseEtas(block, snapshot.eventLines(), pod, finalDestination, structuredEtas),
                events);
    }

    /**
     * A status label can also be printed as a summary heading/value pair.
     * Only an explicit heading counts; "Latest move" is a location.
     */
    private static String summaryStatus(Map<String, String> summary) {
        if (summary == null || summary.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String> entry : summary.entrySet()) {
            String heading = entry.getKey() == null
                    ? ""
                    : entry.getKey().trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            if (heading.equals("status") || heading.equals("shipment status")
                    || heading.equals("current status")) {
                String value = entry.getValue();
                return value == null || value.isBlank() ? null : value.trim();
            }
        }
        return null;
    }

    /**
     * Only publishes an ETA when the line names the monitored node. A line that
     * talks about a transshipment port, or does not identify the node at all,
     * is dropped instead of being guessed as the POD arrival.
     */
    private List<RawEta> parseEtas(String body, List<String> eventLines,
                                   String pod, String finalDestination,
                                   List<RawEta> structuredEtas) {
        List<String> candidates = new ArrayList<>();
        body.lines().forEach(candidates::add);
        candidates.addAll(eventLines);

        Map<TrackedMilestone, RawEta> chosen = new LinkedHashMap<>();
        Set<TrackedMilestone> conflicting = new HashSet<>();

        // The calibrated summary and event table are already bound to their
        // node; the text pass below can only add nodes they did not cover.
        if (structuredEtas != null) {
            for (RawEta eta : structuredEtas) {
                RawEta existing = chosen.putIfAbsent(eta.milestone(), eta);
                if (existing != null && !sameEta(existing, eta)) {
                    conflicting.add(eta.milestone());
                }
            }
        }

        for (String candidate : candidates) {
            // Bind the arrival place and the date to the same field, not to
            // other fields printed on the same row.
            for (String clause : splitClauses(candidate)) {
                Optional<RawEta> eta = etaFromClause(clause, pod, finalDestination);
                if (eta.isEmpty()) {
                    continue;
                }
                TrackedMilestone milestone = eta.get().milestone();
                RawEta existing = chosen.putIfAbsent(milestone, eta.get());
                if (existing != null && !sameEta(existing, eta.get())) {
                    // Two different values for one node: publish neither rather
                    // than guessing which is current.
                    conflicting.add(milestone);
                }
            }
        }
        conflicting.forEach(chosen::remove);
        return new ArrayList<>(chosen.values());
    }

    private static List<String> splitClauses(String candidate) {
        if (candidate == null) {
            return List.of();
        }
        List<String> clauses = new ArrayList<>();
        for (String part : FIELD_SEPARATOR.split(candidate)) {
            if (!part.isBlank()) {
                clauses.add(part);
            }
        }
        return clauses;
    }

    /**
     * One field of a row. The field must itself forecast an arrival, print one
     * date and name the monitored node. A field that names another port is
     * dropped instead of borrowing the row's POD field.
     */
    private Optional<RawEta> etaFromClause(String candidate, String pod,
                                           String finalDestination) {
        String line = candidate == null ? "" : candidate.trim();
        if (line.isEmpty() || !ETA_HINT.matcher(line).find()) {
            return Optional.empty();
        }
        if (!isArrivalForecast(line)) {
            return Optional.empty();
        }
        // A field that names another arrival city cannot borrow the monitored
        // node's label. When neither node is known the remaining checks still
        // have to find an explicit POD/final-destination mention.
        if (namesAnotherArrivalPlace(line, pod, finalDestination)) {
            return Optional.empty();
        }
        // Two dates in one field: the estimate cannot be bound to one value
        // without guessing.
        if (EtaTextParser.dateOccurrences(line) != 1) {
            return Optional.empty();
        }
        Optional<LocalDate> date = EtaTextParser.parseDate(line, dateOrder);
        Optional<TrackedMilestone> milestone = etaMilestone(line, pod, finalDestination);
        if (date.isEmpty() || milestone.isEmpty()) {
            return Optional.empty();
        }
        String rawValue = line.length() > MAX_RAW_VALUE ? line.substring(0, MAX_RAW_VALUE) : line;
        Optional<EtaTextParser.ZonedTime> time = EtaTextParser.parseTime(line);
        return Optional.of(new RawEta(
                milestone.get(),
                rawValue,
                date.get(),
                time.map(EtaTextParser.ZonedTime::time).orElse(null),
                time.map(EtaTextParser.ZonedTime::timezone).orElse(null)));
    }

    /**
     * A usable ETA line forecasts an arrival and does not talk about a
     * departure, a cancellation, cargo handling (loading/unloading/return) or
     * something that already happened. Those categories print dates too, but
     * publishing them as the POD arrival would end tracking on a wrong
     * milestone. The line must also carry explicit arrival wording, so a bare
     * "Scheduled at Vancouver on ..." is not bound to the arrival node.
     */
    private static boolean isArrivalForecast(String line) {
        if (StatusWording.isCancelled(line)) {
            return false;
        }
        if (DEPARTURE_WORDING.matcher(line).find()) {
            return false;
        }
        if (NON_ARRIVAL_OPERATION.matcher(line).find()) {
            return false;
        }
        if (ACTUAL_TIME_WORDING.matcher(line).find()) {
            return false;
        }
        return ARRIVAL_WORDING.matcher(line).find();
    }

    /**
     * True when the field explicitly places the arrival at a city that is
     * neither the monitored port nor the final destination. Only used when one
     * of those is known; otherwise the field is left to the POD/final mention
     * checks below, which already require an explicit binding.
     */
    private static boolean namesAnotherArrivalPlace(String line, String pod, String finalDestination) {
        if (pod == null && finalDestination == null) {
            return false;
        }
        Matcher matcher = ARRIVAL_PLACE.matcher(line);
        while (matcher.find()) {
            String place = matcher.group(1).trim().replaceAll("[\\s.,]+$", "");
            if (place.length() < 3 || isNodeLabel(place)) {
                continue;
            }
            if (sameCity(place, pod) || sameCity(place, finalDestination)) {
                continue;
            }
            return true;
        }
        return false;
    }

    /** Labels that stand for the monitored node itself, not another city. */
    private static boolean isNodeLabel(String place) {
        String value = place.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        return value.equals("pod") || value.startsWith("pod ")
                || value.equals("port of discharge") || value.equals("discharge port")
                || value.equals("port") || value.equals("the port")
                || value.equals("destination") || value.equals("final destination")
                || value.equals("terminal");
    }

    private static boolean sameCity(String left, String right) {
        if (right == null || right.isBlank()) {
            return false;
        }
        return left.split(",")[0].trim().equalsIgnoreCase(right.split(",")[0].trim());
    }

    private static Optional<TrackedMilestone> etaMilestone(String line, String pod, String finalDestination) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.contains("transship") || lower.contains("tranship")
                || lower.contains("transit") || lower.contains("connection")
                || lower.contains("via ")) {
            return Optional.empty();
        }
        boolean namesFinal = lower.contains("final destination");
        boolean namesPod = lower.contains("port of discharge") || lower.contains("discharge port")
                || lower.matches(".*\\bpod\\b.*");
        boolean mentionsPod = containsPlace(lower, pod);
        boolean mentionsFinal = containsPlace(lower, finalDestination);
        // One line naming both nodes cannot be split reliably: the date may
        // belong to either, so it is dropped instead of guessed as the POD.
        if ((namesPod || mentionsPod) && (namesFinal || mentionsFinal)) {
            return Optional.empty();
        }
        if (namesFinal || mentionsFinal) {
            return Optional.of(TrackedMilestone.FINAL_DESTINATION);
        }
        if (namesPod || mentionsPod) {
            return Optional.of(TrackedMilestone.POD_ARRIVAL);
        }
        return Optional.empty();
    }

    private static boolean containsPlace(String haystack, String place) {
        if (place == null || place.isBlank()) {
            return false;
        }
        String city = place.split(",")[0].trim().toLowerCase(Locale.ROOT);
        if (city.length() <= 2) {
            return false;
        }
        // Whole-word match: "Vancouver" must not match inside another token.
        return Pattern.compile("(?<![\\p{L}\\d])" + Pattern.quote(city) + "(?![\\p{L}\\d])")
                .matcher(haystack)
                .find();
    }

    /**
     * The result block must name exactly one container, and it must be the one
     * that was queried. No token means ownership is unknown; two tokens mean
     * the block mixes results. Neither may be reported under this task.
     */
    private static void requireExactlyOneContainer(String block, String containerNo) {
        String expected = normalizeContainer(containerNo);
        Set<String> tokens = new HashSet<>();
        Matcher matcher = CONTAINER_TOKEN.matcher(block);
        while (matcher.find()) {
            tokens.add(normalizeContainer(matcher.group(1)));
        }
        if (tokens.isEmpty()) {
            throw new MscParseException("result block does not identify a container");
        }
        if (tokens.size() > 1) {
            throw new MscParseException("result block shows more than one container");
        }
        if (!expected.isEmpty() && !tokens.contains(expected)) {
            throw new MscParseException("result page is for another container, not " + expected);
        }
    }

    private static String normalizeContainer(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static boolean sameEta(RawEta left, RawEta right) {
        return left.date().equals(right.date())
                && java.util.Objects.equals(left.time(), right.time())
                && java.util.Objects.equals(left.timezone(), right.timezone());
    }

    private static String match(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) {
            return null;
        }
        return clean(matcher.group(1));
    }

    /** Every distinct value captured by a pattern, in page order. */
    private static List<String> matches(Pattern pattern, String body) {
        List<String> values = new ArrayList<>();
        Matcher matcher = pattern.matcher(body);
        while (matcher.find()) {
            String value = clean(matcher.group(1));
            if (value != null) {
                values.add(value);
            }
        }
        return values;
    }

    private static Set<String> distinct(List<String> values) {
        Set<String> distinct = new java.util.LinkedHashSet<>();
        for (String value : values) {
            distinct.add(value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
        }
        return distinct;
    }

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[\\s|;]+$", "").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

}
