package com.shiptrack.contract.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Query attempt status. Wire values are lower-case.
 *
 * <p>Source: 接口文档_终稿.md §2.4</p>
 */
public enum AttemptStatus {
    QUEUED("queued"),
    RUNNING("running"),
    SUCCESS("success"),
    FAILED("failed"),
    SKIPPED("skipped");

    private final String wireValue;

    AttemptStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static AttemptStatus fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (AttemptStatus status : values()) {
            if (status.wireValue.equals(value) || status.name().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown attempt_status: " + value);
    }
}
