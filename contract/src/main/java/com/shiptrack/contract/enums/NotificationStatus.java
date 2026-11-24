package com.shiptrack.contract.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Notification delivery status. Wire values are lower-case.
 *
 * <p>Source: 接口文档_终稿.md §2.9 / 架构设计文档_终稿.md §4.5</p>
 */
public enum NotificationStatus {
    PENDING("pending"),
    SENDING("sending"),
    SENT("sent"),
    FAILED("failed");

    private final String wireValue;

    NotificationStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static NotificationStatus fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (NotificationStatus status : values()) {
            if (status.wireValue.equals(value) || status.name().equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown notification.status: " + value);
    }
}
