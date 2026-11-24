package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Body of {@code PATCH /config/status-mappings}; mappings are replaced wholesale
 * and the version is incremented by the server.
 *
 * <p>Source: 接口文档_终稿.md §5.18</p>
 */
public record StatusMappingsPatchDto(
        @JsonProperty("mappings") List<StatusMappingDto> mappings
) {
}
