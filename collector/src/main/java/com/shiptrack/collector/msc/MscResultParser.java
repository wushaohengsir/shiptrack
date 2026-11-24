package com.shiptrack.collector.msc;

import com.shiptrack.contract.enums.TrackedMilestone;
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
     * Only explicit forecast wording marks a value as an ETA. "Actual arrival
     * at Vancouver" is a fact, not an estimate, and must not be published as
     * the POD ETA.
     */
    private static final Pattern ETA_HINT = Pattern.compile(
            "(?i)\\b(eta|estimated|expected|planned|scheduled)\\b");

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

    private static final int MAX_RAW_VALUE = 200;

    public ParsedTracking parse(MscPageSnapshot snapshot, String containerNo) {
        // Only the result block this query produced may be read. The whole page
        // can hold another container's result (a previous search, a highlighted
        // demo row), and reporting it as this container's status is exactly the
        // premature-stop bug AC-29 forbids.
        String block = snapshot.resultBlockText();
        if (block == null || block.isBlank()) {
            throw new MscParseException("no visible result block for container " + containerNo);
        }
        requireExactlyOneContainer(block, containerNo);

        String rawStatus = firstNonBlank(snapshot.statusText(), match(STATUS_LINE, block));
        if (rawStatus == null) {
            throw new MscParseException("no status text found for container " + containerNo);
        }

        String pol = match(POL, block);
        String pod = match(POD, block);
        String finalDestination = match(FINAL_DESTINATION, block);

        List<RawEvent> events = new ArrayList<>();
        for (String line : snapshot.eventLines()) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Optional<EtaTextParser.ZonedTime> time = EtaTextParser.parseTime(trimmed);
            events.add(new RawEvent(
                    trimmed,
                    StatusWording.eventTimeType(trimmed),
                    EtaTextParser.parseDateTime(trimmed).orElse(null),
                    time.map(EtaTextParser.ZonedTime::timezone).orElse(null)));
        }

        return new ParsedTracking(
                rawStatus,
                match(LOCATION, block),
                match(VESSEL, block),
                match(VOYAGE, block),
                pol,
                pod,
                finalDestination,
                parseEtas(block, snapshot.eventLines(), pod, finalDestination),
                events);
    }

    /**
     * Only publishes an ETA when the line names the monitored node. A line that
     * talks about a transshipment port, or does not identify the node at all,
     * is dropped instead of being guessed as the POD arrival.
     */
    private List<RawEta> parseEtas(String body, List<String> eventLines,
                                   String pod, String finalDestination) {
        List<String> candidates = new ArrayList<>();
        body.lines().forEach(candidates::add);
        candidates.addAll(eventLines);

        Map<TrackedMilestone, RawEta> chosen = new LinkedHashMap<>();
        Set<TrackedMilestone> conflicting = new HashSet<>();

        for (String candidate : candidates) {
            String line = candidate == null ? "" : candidate.trim();
            if (line.isEmpty() || !ETA_HINT.matcher(line).find()) {
                continue;
            }
            if (!isArrivalForecast(line)) {
                continue;
            }
            // Two dates on one line: the estimate cannot be bound to one value
            // without guessing.
            if (EtaTextParser.dateOccurrences(line) != 1) {
                continue;
            }
            Optional<LocalDate> date = EtaTextParser.parseDate(line);
            Optional<TrackedMilestone> milestone = etaMilestone(line, pod, finalDestination);
            if (date.isEmpty() || milestone.isEmpty()) {
                continue;
            }
            String rawValue = line.length() > MAX_RAW_VALUE ? line.substring(0, MAX_RAW_VALUE) : line;
            Optional<EtaTextParser.ZonedTime> time = EtaTextParser.parseTime(line);
            RawEta eta = new RawEta(
                    milestone.get(),
                    rawValue,
                    date.get(),
                    time.map(EtaTextParser.ZonedTime::time).orElse(null),
                    time.map(EtaTextParser.ZonedTime::timezone).orElse(null));

            RawEta existing = chosen.putIfAbsent(milestone.get(), eta);
            if (existing != null && !sameEta(existing, eta)) {
                // Two different values for one node: publish neither rather
                // than guessing which is current.
                conflicting.add(milestone.get());
            }
        }
        conflicting.forEach(chosen::remove);
        return new ArrayList<>(chosen.values());
    }

    /**
     * A usable ETA line forecasts an arrival and does not talk about a
     * departure, a cancellation or something that already happened. Those
     * categories print dates too, but publishing them as the POD arrival would
     * end tracking on a wrong milestone.
     */
    private static boolean isArrivalForecast(String line) {
        if (StatusWording.isCancelled(line)) {
            return false;
        }
        if (DEPARTURE_WORDING.matcher(line).find()) {
            return false;
        }
        return !ACTUAL_TIME_WORDING.matcher(line).find();
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

    private static String clean(String value) {
        if (value == null) {
            return null;
        }
        String cleaned = value.replaceAll("[\\s|;]+$", "").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
