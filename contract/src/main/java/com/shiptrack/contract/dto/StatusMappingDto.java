package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.enums.TransportStatus;

/**
 * One row of {@code config/status-mappings.yaml}.
 *
 * <p>Source: 接口文档_终稿.md §5.17</p>
 */
public record StatusMappingDto(
        @JsonProperty("carrier") String carrier,
        @JsonProperty("match") String match,
        @JsonProperty("transport_status") TransportStatus transportStatus,
        @JsonProperty("priority") int priority
) {
}
