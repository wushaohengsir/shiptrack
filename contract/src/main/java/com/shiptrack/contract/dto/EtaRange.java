package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

/**
 * ETA range over the tracked milestone of active containers.
 * All fields are null/zero when no valid ETA exists.
 *
 * <p>Source: 接口文档_终稿.md §3.1</p>
 */
public record EtaRange(
        @JsonProperty("earliest") LocalDate earliest,
        @JsonProperty("latest") LocalDate latest,
        @JsonProperty("count") int count
) {
}
