package com.shiptrack.collector.browser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Decides whether a single request is aborted before it leaves the browser.
 * Pure and deterministic so it can be unit tested without starting Chromium.
 */
public final class ResourceBlockPolicy {

    private final Set<String> blockedTypes;
    private final List<String> blockedHostFragments;

    public ResourceBlockPolicy(List<String> blockedTypes, List<String> blockedHostFragments) {
        this.blockedTypes = blockedTypes == null
                ? Set.of()
                : blockedTypes.stream()
                        .filter(t -> t != null && !t.isBlank())
                        .map(t -> t.trim().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
        this.blockedHostFragments = blockedHostFragments == null
                ? List.of()
                : new ArrayList<>(blockedHostFragments.stream()
                        .filter(h -> h != null && !h.isBlank())
                        .map(h -> h.trim().toLowerCase(Locale.ROOT))
                        .toList());
    }

    public boolean isBlocked(String resourceType, String url) {
        if (resourceType != null && blockedTypes.contains(resourceType.toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        for (String fragment : blockedHostFragments) {
            if (lower.contains(fragment)) {
                return true;
            }
        }
        return false;
    }
}
