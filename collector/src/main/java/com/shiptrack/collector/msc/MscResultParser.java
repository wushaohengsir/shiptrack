package com.shiptrack.collector.msc;

import com.shiptrack.contract.enums.TrackedMilestone;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    private static final Pattern ETA_HINT = Pattern.compile(
            "(?i)(\\beta\\b|estimated\\s+(?:time\\s+of\\s+)?arrival|arrival\\s+at|expected\\s+arrival)");

    private static final int MAX_RAW_VALUE = 200;

    public ParsedTracking parse(MscPageSnapshot snapshot, String containerNo) {
        String body = snapshot.bodyText() == null ? "" : snapshot.bodyText();

        String rawStatus = firstNonBlank(snapshot.statusText(), match(STATUS_LINE, body));
        if (rawStatus == null) {
            throw new MscParseException("no status text found for container " + containerNo);
        }

        List<RawEvent> events = new ArrayList<>();
        for (String line : snapshot.eventLines()) {
            String trimmed = line == null ? "" : line.trim();
            if (!trimmed.isEmpty()) {
                events.add(new RawEvent(trimmed));
            }
        }

        return new ParsedTracking(
                rawStatus,
                match(LOCATION, body),
                match(VESSEL, body),
                match(VOYAGE, body),
                match(POL, body),
                match(POD, body),
                match(FINAL_DESTINATION, body),
                parseEtas(body, snapshot.eventLines()),
                events);
    }

    private List<RawEta> parseEtas(String body, List<String> eventLines) {
        Set<String> seen = new LinkedHashSet<>();
        List<RawEta> etas = new ArrayList<>();
        List<String> candidates = new ArrayList<>();
        body.lines().forEach(candidates::add);
        candidates.addAll(eventLines);

        for (String candidate : candidates) {
            String line = candidate == null ? "" : candidate.trim();
            if (line.isEmpty() || !ETA_HINT.matcher(line).find()) {
                continue;
            }
            Optional<LocalDate> date = EtaTextParser.parseDate(line);
            if (date.isEmpty()) {
                continue;
            }
            String rawValue = line.length() > MAX_RAW_VALUE ? line.substring(0, MAX_RAW_VALUE) : line;
            TrackedMilestone milestone = line.toLowerCase(Locale.ROOT).contains("final destination")
                    ? TrackedMilestone.FINAL_DESTINATION
                    : TrackedMilestone.POD_ARRIVAL;
            if (!seen.add(milestone + "|" + rawValue)) {
                continue;
            }
            Optional<EtaTextParser.ZonedTime> time = EtaTextParser.parseTime(line);
            etas.add(new RawEta(
                    milestone,
                    rawValue,
                    date.get(),
                    time.map(EtaTextParser.ZonedTime::time).orElse(null),
                    time.map(EtaTextParser.ZonedTime::timezone).orElse(null)));
        }
        return etas;
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
