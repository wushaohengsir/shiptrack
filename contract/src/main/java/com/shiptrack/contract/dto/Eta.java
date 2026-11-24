package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.enums.EtaPrecision;
import com.shiptrack.contract.enums.TrackedMilestone;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * ETA of one milestone.
 *
 * <p>Constraints (接口文档_终稿.md §3.3):
 * {@code precision=DATE} requires {@code date} and forbids {@code time}/{@code utcAt};
 * {@code precision=DATETIME} requires {@code date}, {@code time}, {@code timezone} and {@code utcAt}.</p>
 */
public record Eta(
        @JsonProperty("milestone") TrackedMilestone milestone,
        @JsonProperty("raw_value") String rawValue,
        @JsonProperty("date") LocalDate date,
        @JsonProperty("time") LocalTime time,
        @JsonProperty("timezone") String timezone,
        @JsonProperty("precision") EtaPrecision precision,
        @JsonProperty("utc_at") Instant utcAt
) {
}
