package com.shiptrack.contract.enums;

/**
 * Data health of a container. Wire values are the enum names.
 *
 * <p>Display priority: {@code STALE > PARSE_ERROR > QUERY_FAILED > OK}.
 * Source: 接口文档_终稿.md §2.3 / §8.5</p>
 */
public enum TrackingHealth {
    OK(0),
    QUERY_FAILED(1),
    PARSE_ERROR(2),
    STALE(3);

    private final int displayPriority;

    TrackingHealth(int displayPriority) {
        this.displayPriority = displayPriority;
    }

    public int displayPriority() {
        return displayPriority;
    }
}
