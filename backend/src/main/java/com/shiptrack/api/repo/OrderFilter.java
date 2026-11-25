package com.shiptrack.api.repo;

/**
 * Query parameters of {@code GET /orders} (§5.2). All values are optional.
 */
public record OrderFilter(
        String keyword,
        String transportStatus,
        String etaRisk,
        String trackingHealth,
        boolean includeArchived,
        String sort,
        int page,
        int pageSize
) {
}
