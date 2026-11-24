package com.shiptrack.contract.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Notification channel. Wire values are lower-case.
 *
 * <p>Source: 接口文档_终稿.md §3.7</p>
 */
public enum NotificationChannel {
    FEISHU("feishu");

    private final String wireValue;

    NotificationChannel(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static NotificationChannel fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (NotificationChannel channel : values()) {
            if (channel.wireValue.equals(value) || channel.name().equals(value)) {
                return channel;
            }
        }
        throw new IllegalArgumentException("Unknown channel: " + value);
    }
}
