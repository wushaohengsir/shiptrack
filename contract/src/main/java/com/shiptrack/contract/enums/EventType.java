package com.shiptrack.contract.enums;

/**
 * Business event type. Wire values are the enum names.
 *
 * <p>Source: 接口文档_终稿.md §2.7</p>
 */
public enum EventType {
    ETA_CHANGED,
    ETA_RECOVERED,
    STATUS_CHANGED,
    STATUS_CORRECTED,
    QUERY_FAILED,
    DATA_STALE,
    QUERY_RECOVERED
}
