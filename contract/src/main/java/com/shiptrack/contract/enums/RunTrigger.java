package com.shiptrack.contract.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How a tracking run was triggered. Wire values are lower-case.
 *
 * <p>Source: 接口文档_终稿.md §3.5 / §5.21</p>
 */
public enum RunTrigger {
    SCHEDULE("schedule"),
    MANUAL_CONTAINER("manual_container"),
    MANUAL_ORDER("manual_order"),
    MANUAL_ALL("manual_all");

    private final String wireValue;

    RunTrigger(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static RunTrigger fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (RunTrigger trigger : values()) {
            if (trigger.wireValue.equals(value) || trigger.name().equals(value)) {
                return trigger;
            }
        }
        throw new IllegalArgumentException("Unknown run_trigger: " + value);
    }
}
