package com.shiptrack.contract;

import com.shiptrack.contract.enums.AttemptStatus;
import com.shiptrack.contract.enums.EtaRisk;
import com.shiptrack.contract.enums.EventTimeType;
import com.shiptrack.contract.enums.EventType;
import com.shiptrack.contract.enums.NotificationChannel;
import com.shiptrack.contract.enums.NotificationStatus;
import com.shiptrack.contract.enums.OrderNotificationState;
import com.shiptrack.contract.enums.RunTrigger;
import com.shiptrack.contract.enums.TrackedMilestone;
import com.shiptrack.contract.enums.TrackingHealth;
import com.shiptrack.contract.enums.TransportStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ContractEnumTest {

    @Test
    void transportStatusDictionaryMatchesInterfaceDoc() {
        assertEquals(16, TransportStatus.values().length);
        assertEquals(TransportStatus.PENDING, TransportStatus.valueOf("PENDING"));
        assertEquals(TransportStatus.UNKNOWN, TransportStatus.valueOf("UNKNOWN"));
    }

    @Test
    void etaRiskAndHealthDictionariesAreStable() {
        assertEquals(4, EtaRisk.values().length);
        assertEquals(4, TrackingHealth.values().length);
        assertEquals(3, TrackingHealth.STALE.displayPriority());
    }

    @Test
    void lowercaseWireEnumsRoundTrip() {
        assertEquals("queued", AttemptStatus.QUEUED.wireValue());
        assertEquals(AttemptStatus.SKIPPED, AttemptStatus.fromWire("skipped"));
        assertEquals("manual_container", RunTrigger.MANUAL_CONTAINER.wireValue());
        assertEquals(NotificationStatus.SENDING, NotificationStatus.fromWire("sending"));
        assertEquals("feishu", NotificationChannel.FEISHU.wireValue());
        assertEquals(EventTimeType.CANCELLED, EventTimeType.fromWire("cancelled"));
        assertEquals("pending_order_aggregation",
                OrderNotificationState.PENDING_ORDER_AGGREGATION.wireValue());
    }

    @Test
    void uppercaseEnumsKeepTheirNames() {
        assertEquals("ETA_CHANGED", EventType.ETA_CHANGED.name());
        assertEquals("FINAL_DESTINATION", TrackedMilestone.FINAL_DESTINATION.name());
    }

    @Test
    void unknownWireValueIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> AttemptStatus.fromWire("done"));
        assertThrows(IllegalArgumentException.class, () -> RunTrigger.fromWire("cron"));
    }
}
