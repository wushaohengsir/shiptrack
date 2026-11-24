package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Response of {@code GET /stats/overview}. Counters are containers, not orders.
 *
 * <p>Source: 接口文档_终稿.md §5.19</p>
 */
public record StatsOverviewDto(
        @JsonProperty("unit") String unit,
        @JsonProperty("definitions") StatsDefinitionsDto definitions,
        @JsonProperty("total_active") int totalActive,
        @JsonProperty("normal") int normal,
        @JsonProperty("arriving_soon") int arrivingSoon,
        @JsonProperty("delayed") int delayed,
        @JsonProperty("tracking_issue") int trackingIssue
) {
}
