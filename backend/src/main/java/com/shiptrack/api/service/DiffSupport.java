package com.shiptrack.api.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shiptrack.api.repo.ContainerRecord;
import com.shiptrack.contract.dto.Eta;
import com.shiptrack.contract.dto.SourceEvent;
import com.shiptrack.contract.enums.EtaPrecision;
import com.shiptrack.contract.enums.EtaRisk;
import com.shiptrack.contract.enums.TransportStatus;
import com.shiptrack.contract.internal.TrackingResultReport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure functions for §8.4: canonical content hash, event keys, ETA deltas and
 * threshold comparisons. Kept separate so the report transaction stays readable.
 */
public final class DiffSupport {

    private DiffSupport() {
    }

    /** §8.4 content_hash; excludes HTML, fetch time, tokens and raw_payload. */
    public static String contentHash(TrackingResultReport report) {
        Map<String, Object> root = new TreeMap<>();
        root.put("transport_status", report.transportStatus() == null
                ? null : report.transportStatus().name());
        root.put("etas", canonicalEtas(report.etas()));
        root.put("location", report.location());
        root.put("vessel", report.vessel());
        root.put("voyage", report.voyage());
        root.put("pol", report.pol());
        root.put("pod", report.pod());
        root.put("final_destination", report.finalDestination());
        root.put("source_events", canonicalSourceEvents(report.sourceEvents()));
        return "sha256:" + sha256Hex(toJson(root));
    }

    public static String etaEventKey(long containerId, String eventType, Long fromSnapshotId,
                                     long toSnapshotId) {
        return "evt_" + sha256Hex(containerId + "|eta|" + eventType + "|" + fromSnapshotId
                + "|" + toSnapshotId);
    }

    public static String statusEventKey(long containerId, String eventType, Long fromSnapshotId,
                                        long toSnapshotId) {
        return "evt_" + sha256Hex(containerId + "|transport_status|" + eventType + "|"
                + fromSnapshotId + "|" + toSnapshotId);
    }

    public static String healthEventKey(long containerId, String eventType, Long fromSnapshotId,
                                        long toSnapshotId) {
        return "evt_" + sha256Hex(containerId + "|" + eventType + "|" + fromSnapshotId + "|"
                + toSnapshotId);
    }

    /** ETA of the monitored milestone inside one snapshot's array. */
    public static Eta etaForMilestone(List<Eta> etas, ContainerRecord container) {
        if (etas == null) {
            return null;
        }
        for (Eta eta : etas) {
            if (eta != null && eta.milestone() == container.trackedMilestone()) {
                return eta;
            }
        }
        return null;
    }

    /**
     * Direction-aware threshold test (§8.4): DATETIME pairs use hours, any DATE
     * side falls back to whole local days.
     */
    public static boolean etaChanged(Eta previous, Eta current,
                                     AppConfigService.AppConfigValues config) {
        if (previous == null && current == null) {
            return false;
        }
        if (previous == null || current == null) {
            return true;
        }
        if (previous.precision() == EtaPrecision.DATETIME && current.precision() == EtaPrecision.DATETIME
                && previous.utcAt() != null && current.utcAt() != null) {
            long hours = Duration.between(previous.utcAt(), current.utcAt()).toHours();
            long threshold = hours >= 0
                    ? config.etaDelayThresholdHours() : config.etaEarlyThresholdHours();
            return Math.abs(hours) >= threshold;
        }
        if (previous.date() == null || current.date() == null) {
            return true;
        }
        long days = ChronoUnit.DAYS.between(previous.date(), current.date());
        long threshold = days >= 0
                ? config.etaDelayThresholdDays() : config.etaEarlyThresholdDays();
        return Math.abs(days) >= threshold;
    }

    /** Risk of the current ETA relative to the immutable baseline (§3.1). */
    public static EtaRisk riskAgainstBaseline(Eta baseline, Eta current,
                                              AppConfigService.AppConfigValues config) {
        if (current == null) {
            return EtaRisk.NO_ETA;
        }
        if (baseline == null) {
            return EtaRisk.NORMAL;
        }
        boolean later;
        long magnitude;
        String unit;
        if (baseline.precision() == EtaPrecision.DATETIME && current.precision() == EtaPrecision.DATETIME
                && baseline.utcAt() != null && current.utcAt() != null) {
            long hours = Duration.between(baseline.utcAt(), current.utcAt()).toHours();
            later = hours > 0;
            magnitude = Math.abs(hours);
            unit = "hour";
            long threshold = later ? config.etaDelayThresholdHours() : config.etaEarlyThresholdHours();
            if (magnitude >= threshold) {
                return later ? EtaRisk.DELAYED : EtaRisk.EARLY;
            }
            return EtaRisk.NORMAL;
        }
        if (baseline.date() == null || current.date() == null) {
            return EtaRisk.NORMAL;
        }
        long days = ChronoUnit.DAYS.between(baseline.date(), current.date());
        later = days > 0;
        magnitude = Math.abs(days);
        unit = "day";
        long threshold = later ? config.etaDelayThresholdDays() : config.etaEarlyThresholdDays();
        if (magnitude >= threshold) {
            return later ? EtaRisk.DELAYED : EtaRisk.EARLY;
        }
        return EtaRisk.NORMAL;
    }

    /** {@code {"value": n, "unit": "day"}} for the baseline delta, null-safe. */
    public static Map<String, Object> baselineDelta(Eta baseline, Eta current) {
        if (baseline == null || current == null) {
            return null;
        }
        Map<String, Object> delta = new LinkedHashMap<>();
        if (baseline.precision() == EtaPrecision.DATETIME && current.precision() == EtaPrecision.DATETIME
                && baseline.utcAt() != null && current.utcAt() != null) {
            delta.put("value", Duration.between(baseline.utcAt(), current.utcAt()).toHours());
            delta.put("unit", "hour");
        } else if (baseline.date() != null && current.date() != null) {
            delta.put("value", ChronoUnit.DAYS.between(baseline.date(), current.date()));
            delta.put("unit", "day");
        } else {
            return null;
        }
        return delta;
    }

    public static Map<String, Object> etaValue(Eta eta) {
        if (eta == null) {
            return null;
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("date", eta.date() == null ? null : eta.date().toString());
        value.put("time", eta.time() == null ? null : eta.time().toString());
        value.put("precision", eta.precision());
        value.put("timezone", eta.timezone());
        return value;
    }

    public static Map<String, Object> statusValue(TransportStatus status) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("transport_status", status == null ? null : status.name());
        return value;
    }

    public static long dayGap(Eta baseline, Eta current) {
        if (baseline == null || current == null
                || baseline.date() == null || current.date() == null) {
            return 0;
        }
        return ChronoUnit.DAYS.between(baseline.date(), current.date());
    }

    public static LocalDate dateOf(Eta eta) {
        return eta == null ? null : eta.date();
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static List<Map<String, Object>> canonicalEtas(List<Eta> etas) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (etas == null) {
            return result;
        }
        for (Eta eta : etas) {
            if (eta == null) {
                continue;
            }
            Map<String, Object> item = new TreeMap<>();
            item.put("milestone", eta.milestone() == null ? null : eta.milestone().name());
            item.put("date", eta.date() == null ? null : eta.date().toString());
            item.put("time", eta.time() == null ? null : eta.time().toString());
            item.put("timezone", eta.timezone());
            item.put("precision", eta.precision() == null ? null : eta.precision().name());
            result.add(item);
        }
        result.sort(Comparator.comparing(DiffSupport::toJson));
        return result;
    }

    private static List<Map<String, Object>> canonicalSourceEvents(List<SourceEvent> events) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (events == null) {
            return result;
        }
        for (SourceEvent event : events) {
            if (event == null) {
                continue;
            }
            Map<String, Object> item = new TreeMap<>();
            item.put("milestone", event.milestone() == null ? null : event.milestone().name());
            item.put("event_time_type", event.eventTimeType() == null
                    ? null : event.eventTimeType().wireValue());
            item.put("occurred_at", event.occurredAt() == null ? null : event.occurredAt().toString());
            item.put("timezone", event.timezone());
            item.put("location", event.location());
            item.put("vessel", event.vessel());
            item.put("voyage", event.voyage());
            item.put("raw_text", event.rawText());
            result.add(item);
        }
        result.sort(Comparator.comparing(DiffSupport::toJson));
        return result;
    }

    static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("canonical JSON failed", error);
        }
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
}
