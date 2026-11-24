package com.shiptrack.contract.enums;

/**
 * Transport status of a container. Wire values are the enum names.
 *
 * <p>Source: 接口文档_终稿.md §2.1</p>
 */
public enum TransportStatus {
    PENDING,
    BOOKED,
    EMPTY_RELEASED,
    EMPTY_PICKED_UP,
    GATE_IN,
    LOADED,
    DEPARTED,
    TRANSSHIP_DISCHARGED,
    WAITING_TRANSSHIP,
    TRANSSHIP_LOADED,
    ARRIVED_POD,
    DISCHARGED,
    AVAILABLE,
    PICKED_UP,
    EMPTY_RETURNED,
    UNKNOWN
}
