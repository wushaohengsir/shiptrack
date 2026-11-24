package com.shiptrack.contract.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Time semantics of an event. Wire values are lower-case and nullable.
 *
 * <p>Source: 接口文档_终稿.md §2.8</p>
 */
public enum EventTimeType {
    PLANNED("planned"),
    ACTUAL("actual"),
    CANCELLED("cancelled");

    private final String wireValue;

    EventTimeType(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static EventTimeType fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (EventTimeType type : values()) {
            if (type.wireValue.equals(value) || type.name().equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown event_time_type: " + value);
    }
}
