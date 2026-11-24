package com.shiptrack.contract.internal;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.enums.EventType;

/**
 * Event reference returned after a result report.
 *
 * <p>Source: 接口文档_终稿.md §5.23</p>
 */
public record EventRef(
        @JsonProperty("id") long id,
        @JsonProperty("event_type") EventType eventType
) {
}
