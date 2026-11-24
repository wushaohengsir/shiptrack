package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Definitions that make the overview counters unambiguous for the front end.
 *
 * <p>Source: 接口文档_终稿.md §5.19</p>
 */
public record StatsDefinitionsDto(
        @JsonProperty("arriving_soon_days") int arrivingSoonDays,
        @JsonProperty("normal_excludes_arriving_soon") boolean normalExcludesArrivingSoon,
        @JsonProperty("tracking_issue_overlaps") boolean trackingIssueOverlaps,
        @JsonProperty("tracking_issue_includes_no_eta") boolean trackingIssueIncludesNoEta
) {
}
