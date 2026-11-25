package com.shiptrack.collector.msc;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
            // Lookarounds rather than \b: an ISO timestamp has "2026-12-06T…"
            // and both sides of that date are word characters.
            Pattern.compile("(?<!\\d)(\\d{4}-\\d{2}-\\d{2})(?!\\d)"));

    /**
     * Numeric dates such as {@code 07/12/2026}. The separator order is a site
     * property, not something to infer from the value: MSC renders the first
     * component as the day. Ambiguity is resolved by that declared order, and
     * a value that is impossible in that order is dropped rather than silently
     * re-read in the other order.
     */
    private static final Pattern SLASH_DATE = Pattern.compile(
            "(?<![\\d/])(\\d{1,2})/(\\d{1,2})/(\\d{4})(?![\\d/])");

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            formatter("MMM d uuuu"),
            formatter("MMMM d uuuu"),
            formatter("d MMM uuuu"),
            formatter("d MMMM uuuu"),
            formatter("uuuu-MM-dd"));

    /**
     * A time plus an explicit zone. The offset alternative must come before the
     * bare abbreviations, otherwise "14:30 UTC+08:00" would match "UTC" and
     * ignore the "+08:00".
     */
    private static final Pattern TIME = Pattern.compile(
            "(?<!\\d)(\\d{1,2}):(\\d{2})(?::(\\d{2}))?\\s*"
                    + "(Z|(?:UTC|GMT)\\s*[+-]\\s*\\d{1,2}(?::?\\d{2})?"
                    + "|[+-]\\d{1,2}(?::?\\d{2})?"
                    + "|UTC|GMT|EST|EDT|PST|PDT|CET|CEST)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern OFFSET = Pattern.compile(
            "^(?:(?:UTC|GMT))?([+-])(\\d{1,2})(?::?(\\d{2}))?$");

    /**
     * Abbreviations such as {@code PST} are fixed offsets, not region zones:
     * "16:00 PST" stays -08:00 even when Los Angeles is on daylight time. The
     * IANA {@code Etc/GMT±X} zones carry the exact fixed offset and are valid
     * values for {@code Eta.timezone}.
     */
    private static final Map<String, String> ZONES = Map.ofEntries(
            Map.entry("UTC", "UTC"),
            Map.entry("GMT", "UTC"),
            Map.entry("EST", "Etc/GMT+5"),
            Map.entry("EDT", "Etc/GMT+4"),
            Map.entry("PST", "Etc/GMT+8"),
            Map.entry("PDT", "Etc/GMT+7"),
            Map.entry("CET", "Etc/GMT-1"),
            Map.entry("CEST", "Etc/GMT-2"));

    private EtaTextParser() {
    }

    /** How a numeric {@code a/b/yyyy} date is read. MSC uses day-first. */
    public enum DateOrder {
        DAY_FIRST,
        MONTH_FIRST
    }

    public static Optional<LocalDate> parseDate(String text) {
        return parseDate(text, DateOrder.DAY_FIRST);
    }

    public static Optional<LocalDate> parseDate(String text, DateOrder order) {
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
        Matcher slash = SLASH_DATE.matcher(text);
        if (slash.find()) {
            return slashDate(slash, order == null ? DateOrder.DAY_FIRST : order);
        }
        return Optional.empty();
    }

    /**
     * Reads the two numeric components in the declared order only. If that
     * order produces no real calendar date (for example {@code 12/25/2026}
     * read day-first), the value is dropped: switching to the other order
     * would be an unstated guess about the site's locale.
     */
    private static Optional<LocalDate> slashDate(Matcher matcher, DateOrder order) {
        int first = Integer.parseInt(matcher.group(1));
        int second = Integer.parseInt(matcher.group(2));
        int year = Integer.parseInt(matcher.group(3));
        int day = order == DateOrder.MONTH_FIRST ? second : first;
        int month = order == DateOrder.MONTH_FIRST ? first : second;
        if (month < 1 || month > 12) {
            return Optional.empty();
        }
        try {
            LocalDate date = LocalDate.of(year, month, day);
            if (date.getYear() >= 2000 && date.getYear() <= 2100) {
                return Optional.of(date);
            }
        } catch (RuntimeException ignored) {
            // Day out of range for that month (32/13, Feb 30, ...).
        }
        return Optional.empty();
    }

    /**
     * Returns a time only together with an explicit, unambiguous zone. The
     * returned zone is an IANA id when one exists for the offset (whole-hour
     * offsets use {@code Etc/GMT±X}); a half-hour offset keeps its ISO offset
     * id and is downgraded to DATE by the reporter, which must publish an IANA
     * name (§3.3).
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
        int second = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        if (hour > 23 || minute > 59 || second > 59) {
            return Optional.empty();
        }
        Optional<String> zone = zoneOf(matcher.group(4));
        return zone.map(value -> new ZonedTime(LocalTime.of(hour, minute, second), value));
    }

    /**
     * Number of date-shaped values on a line. The ETA parser uses this to drop a
     * line that prints two dates, because it cannot know which one belongs to
     * the estimated-arrival clause.
     */
    public static int dateOccurrences(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        int count = 0;
        for (Pattern pattern : DATE_PATTERNS) {
            Matcher matcher = pattern.matcher(text);
            while (matcher.find()) {
                count++;
            }
        }
        Matcher slash = SLASH_DATE.matcher(text);
        while (slash.find()) {
            count++;
        }
        return count;
    }

    /**
     * A full instant requires the page to print a date, a time and an explicit
     * zone. Anything less is left to the caller as date-only.
     */
    public static Optional<OffsetDateTime> parseDateTime(String text) {
        return parseDateTime(text, DateOrder.DAY_FIRST);
    }

    public static Optional<OffsetDateTime> parseDateTime(String text, DateOrder order) {
        Optional<LocalDate> date = parseDate(text, order);
        Optional<ZonedTime> time = parseTime(text);
        if (date.isEmpty() || time.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(date.get()
                    .atTime(time.get().time())
                    .atZone(ZoneId.of(time.get().timezone()))
                    .toOffsetDateTime());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<String> zoneOf(String rawZone) {
        String token = rawZone.trim().toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
        if (token.equals("Z")) {
            return Optional.of("UTC");
        }
        String abbreviation = ZONES.get(token);
        if (abbreviation != null) {
            return Optional.of(abbreviation);
        }
        Matcher offset = OFFSET.matcher(token);
        if (!offset.matches()) {
            return Optional.empty();
        }
        int sign = "-".equals(offset.group(1)) ? -1 : 1;
        int hours = Integer.parseInt(offset.group(2));
        int minutes = offset.group(3) == null ? 0 : Integer.parseInt(offset.group(3));
        if (hours > 18 || minutes > 59) {
            return Optional.empty();
        }
        int totalSeconds = sign * (hours * 3600 + minutes * 60);
        try {
            return Optional.of(ianaFor(ZoneOffset.ofTotalSeconds(totalSeconds)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /**
     * IANA has fixed-offset names only for whole hours. Half-hour offsets keep
     * their ISO id here; the reporter then falls back to DATE precision so the
     * wire contract never carries a non-IANA timezone.
     */
    private static String ianaFor(ZoneOffset offset) {
        int total = offset.getTotalSeconds();
        if (total % 3600 == 0 && total >= -12 * 3600 && total <= 14 * 3600) {
            int hours = total / 3600;
            if (hours == 0) {
                return "UTC";
            }
            // Etc/GMT-8 is UTC+8: the sign in the IANA name is inverted.
            return hours > 0 ? "Etc/GMT-" + hours : "Etc/GMT+" + (-hours);
        }
        return offset.getId();
    }

    private static DateTimeFormatter formatter(String pattern) {
        return new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(pattern)
                .toFormatter(Locale.ENGLISH)
                // STRICT refuses calendar-impossible dates such as Feb 30
                // instead of silently rolling them into the next month.
                .withResolverStyle(ResolverStyle.STRICT);
    }

    public record ZonedTime(LocalTime time, String timezone) {
    }
}
