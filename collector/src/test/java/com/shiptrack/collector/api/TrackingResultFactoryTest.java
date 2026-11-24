package com.shiptrack.collector.api;

import com.shiptrack.collector.mapping.StatusMapping;
import com.shiptrack.collector.mapping.StatusMappingSet;
import com.shiptrack.collector.msc.MscPageSnapshot;
import com.shiptrack.collector.msc.ParsedTracking;
import com.shiptrack.collector.msc.RawEta;
import com.shiptrack.collector.msc.RawEvent;
import com.shiptrack.collector.msc.TrackingOutcome;
import com.shiptrack.contract.enums.EtaPrecision;
import com.shiptrack.contract.enums.TrackedMilestone;
import com.shiptrack.contract.enums.TransportStatus;
import com.shiptrack.contract.internal.AgentErrorCode;
import com.shiptrack.contract.internal.ClaimedTaskDto;
import com.shiptrack.contract.internal.TrackingResultReport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TrackingResultFactoryTest {

    private static final OffsetDateTime QUERIED_AT =
            OffsetDateTime.of(2026, 11, 24, 21, 0, 0, 0, ZoneOffset.ofHours(8));

    private final TrackingResultFactory factory = new TrackingResultFactory();

    private final StatusMappingSet mappings = new StatusMappingSet(7, List.of(
            new StatusMapping("MSC", "Loaded on vessel", TransportStatus.LOADED, 100),
            new StatusMapping("MSC", "Gate in", TransportStatus.GATE_IN, 90)));

    private final ClaimedTaskDto task = new ClaimedTaskDto(
            10L, "MSDU7172233", "MSC", TrackedMilestone.POD_ARRIVAL,
            5001L, null, "clm_abc", 2);

    @Test
    void buildsASuccessReportWithThePinnedMappingVersion() {
        ParsedTracking parsed = new ParsedTracking(
                "Loaded on vessel CAPE SOUNIO",
                "Busan, KR", "CAPE SOUNIO", "UK647R",
                "Ningbo, CN", "Vancouver, CA", "Toronto, CA",
                List.of(new RawEta(TrackedMilestone.POD_ARRIVAL, "06 Dec 2026 14:30 EST",
                        LocalDate.of(2026, 12, 6), LocalTime.of(14, 30), "America/New_York")),
                List.of(new RawEvent("Gate in")));
        TrackingOutcome.Success outcome = new TrackingOutcome.Success(parsed, page());

        TrackingResultReport report = factory.build("run_1", task, mappings, outcome, 1, QUERIED_AT);

        assertThat(report.runId()).isEqualTo("run_1");
        assertThat(report.containerId()).isEqualTo(10L);
        assertThat(report.claimToken()).isEqualTo("clm_abc");
        assertThat(report.retryCount()).isEqualTo(1);
        assertThat(report.mappingVersion()).isEqualTo(7);
        assertThat(report.success()).isTrue();
        assertThat(report.rawStatus()).isEqualTo("Loaded on vessel CAPE SOUNIO");
        assertThat(report.transportStatus()).isEqualTo(TransportStatus.LOADED);
        assertThat(report.source()).isEqualTo("msc_web");

        assertThat(report.etas()).hasSize(1);
        assertThat(report.etas().getFirst().precision()).isEqualTo(EtaPrecision.DATETIME);
        assertThat(report.etas().getFirst().utcAt())
                .isEqualTo(Instant.parse("2026-12-06T19:30:00Z"));
        assertThat(report.etas().getFirst().timezone()).isEqualTo("America/New_York");

        assertThat(report.sourceEvents()).hasSize(1);
        assertThat(report.sourceEvents().getFirst().milestone()).isEqualTo(TransportStatus.GATE_IN);
    }

    @Test
    void buildsAFailureReportWithoutBusinessFields() {
        TrackingOutcome.Failure outcome = new TrackingOutcome.Failure(AgentErrorCode.TIMEOUT, "page timeout");

        TrackingResultReport report = factory.build("run_1", task, mappings, outcome, 2, QUERIED_AT);

        assertThat(report.success()).isFalse();
        assertThat(report.errorCode()).isEqualTo("TIMEOUT");
        assertThat(report.errorMessage()).isEqualTo("page timeout");
        assertThat(report.rawStatus()).isNull();
        assertThat(report.transportStatus()).isNull();
        assertThat(report.etas()).isEmpty();
        assertThat(report.mappingVersion()).isEqualTo(7);
        assertThat(report.retryCount()).isEqualTo(2);
    }

    private static MscPageSnapshot page() {
        return new MscPageSnapshot("Container Tracking", "https://www.msc.com/track",
                "Status: Loaded on vessel", null, List.of(), false);
    }
}
