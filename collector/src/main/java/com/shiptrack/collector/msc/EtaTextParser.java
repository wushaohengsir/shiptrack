package com.shiptrack.collector.msc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the date formats the carrier page uses. It never invents a value:
 * an unrecognised date yields empty, and a time is only used when the page also
 * printed an unambiguous timezone.
 */
public final class EtaTextParser {

    private static final List<Pattern> DATE_PATTERNS = List.of(
            Pattern.compile("\\b([A-Za-z]{3,9}\\.? \\d{1,2},? \\d{4})\\b"),
            Pattern.compile("\\b(\\d{1,2} [A-Za-z]{3,9}\\.? \\d{4})\\b"),
            Pattern.compile("\\b(\\d{4}-\\d{2}-\\d{2})\\b"));

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            formatter("MMM d uuuu"),
            formatter("MMMM d uuuu"),
            formatter("d MMM uuuu"),
            formatter("d MMMM uuuu"),
            formatter("uuuu-MM-dd"));

    private static final Pattern TIME = Pattern.compile(
            "\\b(\\d{1,2}):(\\d{2})(?:\\s*(UTC|GMT|EST|EDT|PST|PDT|CET|CEST))\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> ZONES = Map.ofEntries(
            Map.entry("UTC", "UTC"),
            Map.entry("GMT", "UTC"),
            Map.entry("EST", "America/New_York"),
            Map.entry("EDT", "America/New_York"),
            Map.entry("PST", "America/Los_Angeles"),
            Map.entry("PDT", "America/Los_Angeles"),
            Map.entry("CET", "Europe/Paris"),
            Map.entry("CEST", "Europe/Paris"));

    private EtaTextParser() {
    }

    public static Optional<LocalDate> parseDate(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        for (Pattern pattern : DATE_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            if (!matcher.find()) {
                continue;
            }
            String candidate = matcher.group(1).replace(",", "").replace(".", "").replaceAll("\\s+", " ").trim();
            for (DateTimeFormatter format : DATE_FORMATS) {
                try {
                    LocalDate date = LocalDate.parse(candidate, format);
                    if (date.getYear() >= 2000 && date.getYear() <= 2100) {
                        return Optional.of(date);
                    }
                } catch (DateTimeParseException ignored) {
                    // try the next format
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Returns a time only together with an explicit, unambiguous zone.
     */
    public static Optional<ZonedTime> parseTime(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = TIME.matcher(text);
        if (!matcher.find()) {
            return Optional.empty();
        }
        int hour = Integer.parseInt(matcher.group(1));
        int minute = Integer.parseInt(matcher.group(2));
        if (hour > 23 || minute > 59) {
            return Optional.empty();
        }
        String zone = ZONES.get(matcher.group(3).toUpperCase(Locale.ROOT));
        if (zone == null) {
            return Optional.empty();
        }
        return Optional.of(new ZonedTime(LocalTime.of(hour, minute), zone));
    }

    private static DateTimeFormatter formatter(String pattern) {
        return new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(pattern)
                .toFormatter(Locale.ENGLISH)
                .withResolverStyle(ResolverStyle.SMART);
    }

    public record ZonedTime(LocalTime time, String timezone) {
    }
}
