package com.shiptrack.contract.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Aggregation state of the order notification returned by the internal report API.
 *
 * <p>Source: 接口文档_终稿.md §5.23</p>
 */
public enum OrderNotificationState {
    PENDING_ORDER_AGGREGATION("pending_order_aggregation"),
    CREATED("created"),
    NOT_APPLICABLE("not_applicable");

    private final String wireValue;

    OrderNotificationState(String wireValue) {
        this.wireValue = wireValue;
    }

    @JsonValue
    public String wireValue() {
        return wireValue;
    }

    @JsonCreator
    public static OrderNotificationState fromWire(String value) {
        if (value == null) {
            return null;
        }
        for (OrderNotificationState state : values()) {
            if (state.wireValue.equals(value) || state.name().equals(value)) {
                return state;
            }
        }
        throw new IllegalArgumentException("Unknown order_notification.state: " + value);
    }
}
