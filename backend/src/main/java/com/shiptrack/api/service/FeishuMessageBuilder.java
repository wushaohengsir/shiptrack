package com.shiptrack.api.service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Renders an order-aggregated Feishu message (§7.1/§7.3) and produces the
 * deterministic trimming ladder of §7.5. Lines are grouped by unique container
 * number (never by event row), sorted by container number ascending, and every
 * candidate keeps the {@code notification_key}; the optional metadata is
 * dropped first, then the largest prefix of container groups that fits.
 */
public final class FeishuMessageBuilder {

    private static final DateTimeFormatter QUERY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** ISO datetime (with optional seconds/fraction/offset) → date only (§7.5 step ③). */
    private static final Pattern ISO_DATETIME = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2})[T ]\\d{2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?"
                    + "(?:Z|[+-]\\d{2}:?\\d{2})?");

    private FeishuMessageBuilder() {
    }

    public static List<String> candidates(Map<String, Object> payload, String notificationKey,
                                          ZoneId zone) {
        String title = text(payload, "title");
        if (title == null || title.isBlank()) {
            title = "【船期变化】";
        }
        Map<String, List<String>> groups = groups(lines(payload));
        List<String> boxOrder = new ArrayList<>(groups.keySet());
        boxOrder.sort(String::compareTo);
        int containers = boxOrder.size();
        String notification = notificationLine(notificationKey);

        Set<String> unique = new LinkedHashSet<>();
        if (containers == 0) {
            unique.add(title + "\n" + metadata(payload, notificationKey, zone));
            unique.add(title + "\n" + notification);
            return new ArrayList<>(unique);
        }

        // Step ①/② of §4.4: the full body keeps every field at full precision;
        // only the metadata is dropped next. Dates are compressed in step ③.
        String allLines = bullets(groupsInOrder(boxOrder, groups));
        unique.add(title + "\n" + allLines + "\n" + metadata(payload, notificationKey, zone));
        unique.add(title + "\n" + allLines + "\n" + notification);

        // Step ③: count + largest prefix of container groups, still full precision.
        for (int keep = containers - 1; keep >= 1; keep--) {
            unique.add(title + "\n共 " + containers + " 个箱号\n"
                    + bullets(groupsInOrder(boxOrder.subList(0, keep), groups)) + "\n"
                    + notification);
        }

        // Step ④: only now compress ETA datetimes to YYYY-MM-DD, again with the
        // largest prefix that fits. The full prefix is included so a single
        // container still gets a compressed candidate.
        Map<String, List<String>> compressed = compressDates(groups);
        for (int keep = containers; keep >= 1; keep--) {
            unique.add(title + "\n共 " + containers + " 个箱号\n"
                    + bullets(groupsInOrder(boxOrder.subList(0, keep), compressed)) + "\n"
                    + notification);
        }
        unique.add(title + "\n共 " + containers + " 个箱号\n" + notification);
        return new ArrayList<>(unique);
    }

    /**
     * Groups raw lines by container number. The first whitespace-separated token
     * is the box number; lines that do not start with one are kept together under
     * themselves so no content is silently lost.
     */
    private static Map<String, List<String>> groups(List<String> lines) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        for (String line : lines) {
            groups.computeIfAbsent(containerOf(line), key -> new ArrayList<>()).add(line);
        }
        return groups;
    }

    /** §7.5 step ③: the same groups with ETA datetimes reduced to their date. */
    private static Map<String, List<String>> compressDates(Map<String, List<String>> groups) {
        Map<String, List<String>> compressed = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : groups.entrySet()) {
            List<String> lines = new ArrayList<>(entry.getValue().size());
            for (String line : entry.getValue()) {
                lines.add(normalizeEta(line));
            }
            compressed.put(entry.getKey(), lines);
        }
        return compressed;
    }

    private static String containerOf(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String[] parts = trimmed.split("\\s+", 2);
        return parts[0];
    }

    private static List<String> groupsInOrder(List<String> boxOrder,
                                              Map<String, List<String>> groups) {
        List<String> lines = new ArrayList<>();
        for (String box : boxOrder) {
            lines.addAll(groups.get(box));
        }
        return lines;
    }

    private static String normalizeEta(String line) {
        return ISO_DATETIME.matcher(line).replaceAll("$1");
    }

    private static String bullets(List<String> lines) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                body.append('\n');
            }
            body.append("• ").append(lines.get(i));
        }
        return body.toString();
    }

    private static String notificationLine(String notificationKey) {
        return "通知标识：" + (notificationKey == null ? "" : notificationKey);
    }

    private static String metadata(Map<String, Object> payload, String notificationKey, ZoneId zone) {
        StringBuilder meta = new StringBuilder();
        String queriedAt = text(payload, "queried_at");
        if (queriedAt != null && !queriedAt.isBlank()) {
            meta.append("本轮查询时间：").append(formatTime(queriedAt, zone)).append('\n');
        }
        String source = text(payload, "source");
        if (source != null && !source.isBlank()) {
            meta.append("数据来源：").append(source).append('\n');
        }
        meta.append(notificationLine(notificationKey));
        return meta.toString();
    }

    private static String formatTime(String value, ZoneId zone) {
        try {
            return QUERY_TIME.format(OffsetDateTime.parse(value)
                    .atZoneSameInstant(zone == null ? ZoneId.of("Asia/Shanghai") : zone));
        } catch (DateTimeParseException error) {
            return value;
        }
    }

    private static String text(Map<String, Object> payload, String key) {
        if (payload == null) {
            return null;
        }
        Object value = payload.get(key);
        return value == null ? null : value.toString();
    }

    private static List<String> lines(Map<String, Object> payload) {
        List<String> lines = new ArrayList<>();
        if (payload == null) {
            return lines;
        }
        Object value = payload.get("lines");
        if (value instanceof List<?> list) {
            for (Object row : list) {
                if (row != null) {
                    lines.add(row.toString());
                }
            }
        }
        return lines;
    }
}
