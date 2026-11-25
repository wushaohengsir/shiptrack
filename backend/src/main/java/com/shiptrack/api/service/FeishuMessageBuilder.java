package com.shiptrack.api.service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Renders an order-aggregated Feishu message (§7.1/§7.3) and produces the
 * deterministic trimming ladder of §7.5 (drop optional metadata, then keep the
 * first N container lines in ascending order).
 */
public final class FeishuMessageBuilder {

    private static final DateTimeFormatter QUERY_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private FeishuMessageBuilder() {
    }

    public static List<String> candidates(Map<String, Object> payload, String notificationKey,
                                          ZoneId zone) {
        String title = text(payload, "title");
        if (title == null || title.isBlank()) {
            title = "【船期变化】";
        }
        List<String> lines = lines(payload);
        List<String> candidates = new ArrayList<>();

        candidates.add(title + "\n" + bullets(lines) + "\n"
                + metadata(payload, notificationKey, zone));
        if (!lines.isEmpty()) {
            candidates.add(title + "\n" + bullets(lines));
        }

        List<String> sorted = new ArrayList<>(lines);
        Collections.sort(sorted);
        int total = sorted.size();
        for (int keep = total - 1; keep >= 1; keep--) {
            candidates.add(title + "\n共 " + total + " 个箱号\n"
                    + bullets(sorted.subList(0, keep)));
        }
        if (total > 0) {
            candidates.add(title + "\n共 " + total + " 个箱号");
        }
        return candidates;
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
        meta.append("通知标识：").append(notificationKey);
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
