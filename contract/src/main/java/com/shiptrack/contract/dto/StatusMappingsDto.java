package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Full status-mapping document.
 *
 * <p>Source: 接口文档_终稿.md §5.17</p>
 */
public record StatusMappingsDto(
        @JsonProperty("version") int version,
        @JsonProperty("updated_at") OffsetDateTime updatedAt,
        @JsonProperty("mappings") List<StatusMappingDto> mappings
) {
}
