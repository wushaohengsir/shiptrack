package com.shiptrack.api.service;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §7.5 trimming: unique-container grouping, stable order, key retention, UTF-8. */
class FeishuMessageBuilderTest {

    private static final String KEY = "order:1:run:R1:channel:feishu";

    @Test
    void oneContainerWithManyEventsCountsOneContainer() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 400; i++) {
            lines.add("MSDU7172233  到港 2026-12-07 → 2026-12-09（较上次推迟 2 天）");
        }
        List<String> candidates = FeishuMessageBuilder.candidates(payload(lines), KEY, null);

        assertTrue(candidates.size() >= 2);
        for (String candidate : candidates) {
            assertTrue(candidate.contains("通知标识：" + KEY), candidate);
            if (candidate.contains("个箱号")) {
                assertTrue(candidate.contains("共 1 个箱号"), candidate);
                assertFalse(candidate.contains("共 400 个箱号"), candidate);
            }
        }
    }

    @Test
    void groupsAreSortedByContainerAscendingAndEventsKeepTheirOrder() {
        List<String> lines = List.of(
                "MSKU0000002  第二条",
                "MSKU0000001  第一条",
                "MSKU0000002  第一条",
                "MSKU0000009  唯一");
        List<String> candidates = FeishuMessageBuilder.candidates(payload(lines), KEY, null);
        String full = candidates.get(0);

        int first = full.indexOf("MSKU0000001  第一条");
        int secondA = full.indexOf("MSKU0000002  第二条");
        int secondB = full.indexOf("MSKU0000002  第一条");
        int third = full.indexOf("MSKU0000009  唯一");
        assertTrue(first >= 0 && secondA > first && secondB > secondA && third > secondB, full);
        assertTrue(candidates.stream().anyMatch(c -> c.contains("共 3 个箱号")));
    }

    @Test
    void everyCandidateKeepsTheNotificationKey() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            lines.add(String.format("MSKU%07d  到港 2026-12-07 → 2026-12-09", i));
        }
        List<String> candidates = FeishuMessageBuilder.candidates(payload(lines), KEY, null);
        for (String candidate : candidates) {
            assertTrue(candidate.contains("通知标识：" + KEY), candidate);
        }
    }

    @Test
    void theFullBodyKeepsPrecisionAndOnlyTheCompressionStageTrims() {
        List<String> lines = List.of(
                "MSDU7172233  到港 2026-12-07 08:00 → 2026-12-09 16:30");
        List<String> candidates = FeishuMessageBuilder.candidates(payload(lines), KEY, null);

        String full = candidates.get(0);
        assertTrue(full.contains("08:00"), full);
        assertTrue(full.contains("16:30"), full);

        String compressed = candidates.stream()
                .filter(candidate -> candidate.contains("到港 2026-12-07 → 2026-12-09"))
                .findFirst().orElseThrow();
        assertFalse(compressed.contains("08:00"), compressed);
        assertFalse(compressed.contains("16:30"), compressed);
    }

    @Test
    void aSingleShortIsoDatetimeOnlyLosesItsTimeInTheCompressionStage() {
        List<String> lines = List.of("MSDU7172233  到港 2026-12-09T13:20:00+08:00");
        List<String> candidates = FeishuMessageBuilder.candidates(payload(lines), KEY, null);

        assertTrue(candidates.get(0).contains("2026-12-09T13:20:00+08:00"), candidates.get(0));
        assertTrue(candidates.stream().anyMatch(candidate ->
                        candidate.contains("到港 2026-12-09") && !candidate.contains("13:20")),
                candidates.toString());
    }

    @Test
    void largestPrefixThatFitsIsDeterministicAndUtf8Safe() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            lines.add(String.format("MSKU%07d  到港 2026-12-07 → 2026-12-09（较上次推迟 2 天；当前风险 延误；航线：宁波 → 温哥华）", i));
        }
        Map<String, Object> payload = payload(lines);
        List<String> first = FeishuMessageBuilder.candidates(payload, KEY, null);
        List<String> second = FeishuMessageBuilder.candidates(payload, KEY, null);

        assertEquals(first, second, "same input must yield the same ladder");

        String chosen = null;
        for (String candidate : first) {
            if (candidate.getBytes(StandardCharsets.UTF_8).length <= FeishuClient.MAX_BODY_BYTES) {
                chosen = candidate;
                break;
            }
        }
        assertTrue(chosen != null, "a trimmed candidate must fit");
        assertEquals(chosen, new String(chosen.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8));
        assertTrue(chosen.contains("通知标识：" + KEY));
    }

    private static Map<String, Object> payload(List<String> lines) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", "【船期变化】PO-001");
        payload.put("lines", lines);
        payload.put("queried_at", "2026-11-22T08:00:00Z");
        payload.put("source", "MSC");
        return payload;
    }
}
