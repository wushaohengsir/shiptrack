package com.shiptrack.contract;

import com.shiptrack.contract.dto.ContainerDto;
import com.shiptrack.contract.dto.EventDto;
import com.shiptrack.contract.dto.OrderDetailResponse;
import com.shiptrack.contract.dto.OrderSummary;
import com.shiptrack.contract.dto.RefreshContainerResponse;
import com.shiptrack.contract.dto.RunNotCreatedResponse;
import com.shiptrack.contract.dto.UpdateContainerResponse;
import com.shiptrack.contract.enums.AttemptStatus;
import com.shiptrack.contract.enums.EtaRisk;
import com.shiptrack.contract.enums.EventTimeType;
import com.shiptrack.contract.enums.EventType;
import com.shiptrack.contract.enums.NotCreatedReason;
import com.shiptrack.contract.enums.NotificationChannel;
import com.shiptrack.contract.enums.NotificationStatus;
import com.shiptrack.contract.enums.OrderNotificationState;
import com.shiptrack.contract.enums.RunTrigger;
import com.shiptrack.contract.enums.TrackedMilestone;
import com.shiptrack.contract.enums.TrackingHealth;
import com.shiptrack.contract.enums.TransportStatus;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests against the real Jackson 3 (Boot 4) ObjectMapper, not just the
 * enum helpers. Guards wire values, snake_case field names and the response DTOs.
 */
class ContractJacksonTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    @Test
    void lowercaseEnumsSerializeAsWireValues() throws Exception {
        assertEquals("\"queued\"", mapper.writeValueAsString(AttemptStatus.QUEUED));
        assertEquals("\"failed\"", mapper.writeValueAsString(AttemptStatus.FAILED));
        assertEquals("\"manual_container\"", mapper.writeValueAsString(RunTrigger.MANUAL_CONTAINER));
        assertEquals("\"planned\"", mapper.writeValueAsString(EventTimeType.PLANNED));
        assertEquals("\"sending\"", mapper.writeValueAsString(NotificationStatus.SENDING));
        assertEquals("\"feishu\"", mapper.writeValueAsString(NotificationChannel.FEISHU));
        assertEquals("\"not_applicable\"",
                mapper.writeValueAsString(OrderNotificationState.NOT_APPLICABLE));
    }

    @Test
    void lowercaseEnumsDeserializeFromWireValues() throws Exception {
        assertEquals(AttemptStatus.SKIPPED, mapper.readValue("\"skipped\"", AttemptStatus.class));
        assertEquals(RunTrigger.MANUAL_ALL, mapper.readValue("\"manual_all\"", RunTrigger.class));
        assertEquals(EventTimeType.CANCELLED, mapper.readValue("\"cancelled\"", EventTimeType.class));
        assertEquals(NotificationStatus.SENT, mapper.readValue("\"sent\"", NotificationStatus.class));
        assertEquals(NotificationChannel.FEISHU, mapper.readValue("\"feishu\"", NotificationChannel.class));
        assertEquals(OrderNotificationState.PENDING_ORDER_AGGREGATION,
                mapper.readValue("\"pending_order_aggregation\"", OrderNotificationState.class));
    }

    @Test
    void unknownWireValueIsRejected() {
        assertThrows(Exception.class, () -> mapper.readValue("\"done\"", AttemptStatus.class));
        assertThrows(Exception.class, () -> mapper.readValue("\"cron\"", RunTrigger.class));
    }

    @Test
    void nullEnumDeserializesToNull() throws Exception {
        assertNull(mapper.readValue("null", AttemptStatus.class));
    }

    @Test
    void uppercaseEnumsSerializeAsNames() throws Exception {
        assertEquals("\"TRANSSHIP_DISCHARGED\"",
                mapper.writeValueAsString(TransportStatus.TRANSSHIP_DISCHARGED));
        assertEquals(TransportStatus.TRANSSHIP_DISCHARGED,
                mapper.readValue("\"TRANSSHIP_DISCHARGED\"", TransportStatus.class));
        assertEquals("\"POD_ARRIVAL\"", mapper.writeValueAsString(TrackedMilestone.POD_ARRIVAL));
        assertEquals("\"ETA_CHANGED\"", mapper.writeValueAsString(EventType.ETA_CHANGED));
    }

    @Test
    void containerDtoUsesSnakeCaseFieldsAndChecksumFlag() throws Exception {
        ContainerDto dto = container(false);
        String json = mapper.writeValueAsString(dto);

        assertTrue(json.contains("\"container_no\":\"MSDU7172233\""), json);
        assertTrue(json.contains("\"checksum_valid\":false"), json);
        assertTrue(json.contains("\"transport_status\":\"TRANSSHIP_DISCHARGED\""), json);
        assertTrue(json.contains("\"tracked_milestone\":\"POD_ARRIVAL\""), json);
        assertTrue(json.contains("\"created_at\":\"2026-11-20T10:00:00+08:00\""), json);
        assertFalse(json.contains("containerNo"), json);
        assertFalse(json.contains("checksumValid"), json);

        ContainerDto back = mapper.readValue(json, ContainerDto.class);
        assertEquals("MSDU7172233", back.containerNo());
        assertFalse(back.checksumValid());
        assertEquals(TransportStatus.TRANSSHIP_DISCHARGED, back.transportStatus());
        assertEquals(dto.createdAt().toInstant(), back.createdAt().toInstant());
    }

    @Test
    void orderDetailResponseCarriesContainersArray() throws Exception {
        OrderDetailResponse detail = new OrderDetailResponse(
                1L, "PO-001", "宁波→多伦多 11 月批次", 1, summary(), null, EtaRisk.DELAYED,
                null, OffsetDateTime.parse("2026-11-20T10:00:00+08:00"),
                OffsetDateTime.parse("2026-11-22T08:05:00+08:00"),
                List.of(container(true)));

        String json = mapper.writeValueAsString(detail);
        assertTrue(json.contains("\"order_no\":\"PO-001\""), json);
        assertTrue(json.contains("\"container_count\":1"), json);
        assertTrue(json.contains("\"containers\":[{"), json);

        OrderDetailResponse back = mapper.readValue(json, OrderDetailResponse.class);
        assertEquals(1, back.containers().size());
        assertEquals("MSDU7172233", back.containers().get(0).containerNo());
        assertTrue(back.containers().get(0).checksumValid());
    }

    @Test
    void updateContainerResponseCarriesBaselineReset() throws Exception {
        UpdateContainerResponse response = new UpdateContainerResponse(
                10L, 1L, "MSDU7172233", "MSC", "40HC", true,
                TrackedMilestone.FINAL_DESTINATION, null, true,
                TransportStatus.DEPARTED, EtaRisk.NORMAL, TrackingHealth.OK, null,
                null, null, null, null, null, null, false, null, null, null, true, null,
                OffsetDateTime.parse("2026-11-20T10:00:00+08:00"),
                OffsetDateTime.parse("2026-11-22T08:05:00+08:00"));

        String json = mapper.writeValueAsString(response);
        assertTrue(json.contains("\"baseline_reset\":true"), json);
        assertTrue(json.contains("\"baseline_snapshot_id\":null"), json);

        UpdateContainerResponse back = mapper.readValue(json, UpdateContainerResponse.class);
        assertTrue(back.baselineReset());
        assertEquals(TrackedMilestone.FINAL_DESTINATION, back.trackedMilestone());
    }

    @Test
    void runNotCreatedResponseMatchesSpec() throws Exception {
        String json = mapper.writeValueAsString(
                new RunNotCreatedResponse(false, NotCreatedReason.NO_ACTIVE_CONTAINERS));

        assertEquals("{\"created\":false,\"reason\":\"NO_ACTIVE_CONTAINERS\"}", json);

        RunNotCreatedResponse back = mapper.readValue(json, RunNotCreatedResponse.class);
        assertFalse(back.created());
        assertEquals(NotCreatedReason.NO_ACTIVE_CONTAINERS, back.reason());
    }

    @Test
    void refreshResponseUsesLowercaseAttemptStatus() throws Exception {
        String json = mapper.writeValueAsString(
                new RefreshContainerResponse("run_1", 10L, AttemptStatus.QUEUED));

        assertEquals("{\"run_id\":\"run_1\",\"container_id\":10,\"status\":\"queued\"}", json);
        assertEquals(AttemptStatus.QUEUED,
                mapper.readValue(json, RefreshContainerResponse.class).status());
    }

    @Test
    void eventDtoUsesLowercaseEventTimeTypeAndUppercaseEventType() throws Exception {
        EventDto event = new EventDto(1L, 2L, null, "evt_1", EventType.STATUS_CHANGED,
                "transport_status", EventTimeType.ACTUAL, null, null, null, null,
                EtaRisk.DELAYED, EtaRisk.NORMAL, null, null, null, null, "msc_web");

        String json = mapper.writeValueAsString(event);
        assertTrue(json.contains("\"event_type\":\"STATUS_CHANGED\""), json);
        assertTrue(json.contains("\"event_time_type\":\"actual\""), json);
        assertTrue(json.contains("\"risk_before\":\"DELAYED\""), json);

        EventDto back = mapper.readValue(json, EventDto.class);
        assertEquals(EventType.STATUS_CHANGED, back.eventType());
        assertEquals(EventTimeType.ACTUAL, back.eventTimeType());
    }

    private static ContainerDto container(boolean checksumValid) {
        return new ContainerDto(
                10L, 1L, "MSDU7172233", "MSC", "40HC", checksumValid,
                TrackedMilestone.POD_ARRIVAL, 5001L,
                TransportStatus.TRANSSHIP_DISCHARGED, EtaRisk.DELAYED, TrackingHealth.OK,
                null, "Ningbo, CN", "Vancouver, CA", "Toronto, CA", "Busan, KR",
                "CAPE SOUNIO", "UK647R", false,
                OffsetDateTime.parse("2026-11-20T10:00:00+08:00"),
                null, null, true, null,
                OffsetDateTime.parse("2026-11-20T10:00:00+08:00"),
                OffsetDateTime.parse("2026-11-22T08:05:00+08:00"));
    }

    private static OrderSummary summary() {
        return new OrderSummary(
                Map.of(TransportStatus.DEPARTED, 1),
                Map.of(EtaRisk.DELAYED, 1),
                Map.of(TrackingHealth.OK, 1));
    }
}
