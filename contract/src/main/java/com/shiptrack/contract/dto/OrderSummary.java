package com.shiptrack.contract.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.shiptrack.contract.enums.EtaRisk;
import com.shiptrack.contract.enums.TrackingHealth;
import com.shiptrack.contract.enums.TransportStatus;

import java.util.Map;

/**
 * Per-order status counters. Every enum key must be present, zero-filled.
 *
 * <p>Source: 接口文档_终稿.md §3.1</p>
 */
public record OrderSummary(
        @JsonProperty("transport_status_counts") Map<TransportStatus, Integer> transportStatusCounts,
        @JsonProperty("eta_risk_counts") Map<EtaRisk, Integer> etaRiskCounts,
        @JsonProperty("tracking_health_counts") Map<TrackingHealth, Integer> trackingHealthCounts
) {
}
