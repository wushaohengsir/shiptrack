package com.shiptrack.collector.mapping;

import com.shiptrack.contract.enums.TransportStatus;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * An immutable mapping snapshot with its version. A task picks one snapshot at
 * claim time and reports that version even if the file changes mid-query.
 *
 * <p>Matching is a case-insensitive substring test on whitespace-normalised
 * text. The highest priority wins; equal priority falls back to the longest
 * match, then to enum declaration order, so the result is deterministic.</p>
 */
public record StatusMappingSet(int version, List<StatusMapping> mappings) {

    public StatusMappingSet {
        if (version < 1) {
            throw new IllegalArgumentException("mapping version must be positive, got " + version);
        }
        mappings = List.copyOf(mappings);
    }

    public TransportStatus normalize(String carrier, String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return TransportStatus.UNKNOWN;
        }
        String haystack = normalizeText(rawText);
        StatusMapping best = null;
        for (StatusMapping mapping : mappings) {
            if (!carrierMatches(mapping.carrier(), carrier)) {
                continue;
            }
            String needle = normalizeText(mapping.match());
            if (needle.isEmpty() || !haystack.contains(needle)) {
                continue;
            }
            if (best == null || BEATS.compare(mapping, best) < 0) {
                best = mapping;
            }
        }
        return best == null ? TransportStatus.UNKNOWN : best.transportStatus();
    }

    /**
     * Negative when {@code left} ranks above {@code right}.
     */
    private static final Comparator<StatusMapping> BEATS = Comparator
            .comparingInt(StatusMapping::priority).reversed()
            .thenComparing(m -> normalizeText(m.match()).length(), Comparator.reverseOrder())
            .thenComparing(m -> m.transportStatus().ordinal());

    private static boolean carrierMatches(String mappingCarrier, String carrier) {
        if (mappingCarrier == null || mappingCarrier.isBlank()) {
            return true;
        }
        return carrier != null && mappingCarrier.trim().equalsIgnoreCase(carrier.trim());
    }

    static String normalizeText(String value) {
        return value.replace('\u00a0', ' ')
                .replaceAll("\\s+", " ")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
