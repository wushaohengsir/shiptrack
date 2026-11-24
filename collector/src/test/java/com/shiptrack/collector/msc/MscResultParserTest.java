package com.shiptrack.collector.msc;

import com.shiptrack.contract.enums.EventTimeType;
import com.shiptrack.contract.enums.TrackedMilestone;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MscResultParserTest {

    private final MscResultParser parser = new MscResultParser();

    private static final String BODY = """
            Container MSDU7172233
            Status: Loaded on vessel CAPE SOUNIO/UK647R at Busan
            Vessel: CAPE SOUNIO
            Voyage: UK647R
            Port of Loading: Ningbo, CN
            Port of Discharge: Vancouver, CA
            Final Destination: Toronto, CA
            Current Location: Busan, KR
            Estimated arrival at POD: Dec 06, 2026
            """;

    @Test
    void extractsFieldsVerbatim() {
        ParsedTracking parsed = parser.parse(snapshot(BODY, null, List.of()), "MSDU7172233");

        assertThat(parsed.rawStatus()).isEqualTo("Loaded on vessel CAPE SOUNIO/UK647R at Busan");
        assertThat(parsed.vessel()).isEqualTo("CAPE SOUNIO");
        assertThat(parsed.voyage()).isEqualTo("UK647R");
        assertThat(parsed.pol()).isEqualTo("Ningbo, CN");
        assertThat(parsed.pod()).isEqualTo("Vancouver, CA");
        assertThat(parsed.finalDestination()).isEqualTo("Toronto, CA");
        assertThat(parsed.location()).isEqualTo("Busan, KR");
    }

    @Test
    void parsesDateOnlyAndDateTimeEtas() {
        ParsedTracking parsed = parser.parse(snapshot(BODY, null, List.of()), "MSDU7172233");

        assertThat(parsed.etas()).hasSize(1);
        RawEta dateOnly = parsed.etas().getFirst();
        assertThat(dateOnly.milestone()).isEqualTo(TrackedMilestone.POD_ARRIVAL);
        assertThat(dateOnly.date()).isEqualTo(LocalDate.of(2026, 12, 6));
        assertThat(dateOnly.time()).isNull();
        assertThat(dateOnly.timezone()).isNull();

        String withTime = """
                Container MSDU7172233
                Status: Loaded on vessel
                Port of Discharge: Vancouver, CA
                Estimated arrival at POD: 06 Dec 2026 14:30 EST
                """;
        RawEta dateTime = parser.parse(snapshot(withTime, null, List.of()), "MSDU7172233")
                .etas().getFirst();
        assertThat(dateTime.date()).isEqualTo(LocalDate.of(2026, 12, 6));
        assertThat(dateTime.time()).isEqualTo(LocalTime.of(14, 30));
        assertThat(dateTime.timezone()).isEqualTo("Etc/GMT+5");
    }

    @Test
    void bindsFinalDestinationEtaToTheFinalDestinationNode() {
        String body = """
                Container MSDU7172233
                Status: Discharged from vessel
                Port of Discharge: Vancouver, CA
                Final Destination: Toronto, CA
                Estimated arrival at final destination: Dec 09, 2026
                """;
        ParsedTracking parsed = parser.parse(snapshot(body, null, List.of()), "MSDU7172233");

        assertThat(parsed.etas()).hasSize(1);
        assertThat(parsed.etas().getFirst().milestone()).isEqualTo(TrackedMilestone.FINAL_DESTINATION);
    }

    @Test
    void dropsTransshipmentEtaInsteadOfBindingItToThePod() {
        String body = """
                Container MSDU7172233
                Status: Transshipment load
                Port of Discharge: Vancouver, CA
                Transshipment arrival at Singapore: Dec 02, 2026
                Estimated arrival at POD: Dec 06, 2026
                """;
        ParsedTracking parsed = parser.parse(snapshot(body, null, List.of()), "MSDU7172233");

        assertThat(parsed.etas()).hasSize(1);
        assertThat(parsed.etas().getFirst().milestone()).isEqualTo(TrackedMilestone.POD_ARRIVAL);
        assertThat(parsed.etas().getFirst().date()).isEqualTo(LocalDate.of(2026, 12, 6));
    }

    @Test
    void dropsAmbiguousEtaThatDoesNotNameTheMonitoredNode() {
        String body = """
                Container MSDU7172233
                Status: Loaded on vessel
                Gateway ETA: 06 Dec 2026 14:30 EST
                """;
        ParsedTracking parsed = parser.parse(snapshot(body, null, List.of()), "MSDU7172233");
        assertThat(parsed.etas()).isEmpty();
    }

    @Test
    void refusesConflictingEtasForTheSameNode() {
        String body = """
                Container MSDU7172233
                Status: Loaded on vessel
                Estimated arrival at POD: Dec 06, 2026
                Estimated arrival at port of discharge: Dec 08, 2026
                """;
        ParsedTracking parsed = parser.parse(snapshot(body, null, List.of()), "MSDU7172233");
        assertThat(parsed.etas()).isEmpty();
    }

    @Test
    void fallsBackToTheStatusLabelWhenTheSelectorFoundNothing() {
        ParsedTracking parsed = parser.parse(
                snapshot("Container MSDU7172233\nStatus: Gate in", null, List.of()), "MSDU7172233");
        assertThat(parsed.rawStatus()).isEqualTo("Gate in");
    }

    @Test
    void keepsTimelineRowsAsRawEvents() {
        ParsedTracking parsed = parser.parse(
                snapshot(BODY, null, List.of("Gate in - Oct 21", "Loaded on vessel - Oct 24")),
                "MSDU7172233");
        assertThat(parsed.events()).extracting(RawEvent::rawText)
                .containsExactly("Gate in - Oct 21", "Loaded on vessel - Oct 24");
    }

    @Test
    void extractsEventTimeAndSemanticsWhenThePageStatesThem() {
        ParsedTracking parsed = parser.parse(
                snapshot(BODY, null, List.of(
                        "Gate in - 2026-11-21 16:20 CET",
                        "Estimated arrival at POD - Dec 06, 2026 14:30 EST",
                        "Vessel departure cancelled - 2026-11-25 09:00 UTC")),
                "MSDU7172233");

        RawEvent actual = parsed.events().get(0);
        assertThat(actual.eventTimeType()).isEqualTo(EventTimeType.ACTUAL);
        assertThat(actual.occurredAt())
                .isEqualTo(OffsetDateTime.parse("2026-11-21T16:20:00+01:00"));
        assertThat(actual.timezone()).isEqualTo("Etc/GMT-1");

        RawEvent planned = parsed.events().get(1);
        assertThat(planned.eventTimeType()).isEqualTo(EventTimeType.PLANNED);
        assertThat(planned.occurredAt())
                .isEqualTo(OffsetDateTime.parse("2026-12-06T14:30:00-05:00"));

        RawEvent cancelled = parsed.events().get(2);
        assertThat(cancelled.eventTimeType()).isEqualTo(EventTimeType.CANCELLED);
        assertThat(cancelled.occurredAt()).isEqualTo(OffsetDateTime.parse("2026-11-25T09:00:00Z"));
    }

    @Test
    void anActualArrivalIsAFactAndNotAnEta() {
        String body = """
                Container MSDU7172233
                Status: Discharged from vessel
                Port of Discharge: Vancouver, CA
                Actual arrival at Vancouver on 2026-11-21
                """;
        ParsedTracking parsed = parser.parse(snapshot(body, null, List.of()), "MSDU7172233");
        assertThat(parsed.etas()).isEmpty();
    }

    @Test
    void dropsALineThatNamesBothThePodAndTheFinalDestination() {
        String body = """
                Container MSDU7172233
                Status: Discharged from vessel
                Port of Discharge: Vancouver, CA
                Final Destination: Toronto, CA
                ETA Vancouver (POD) and final destination Toronto on 2026-12-06
                """;
        ParsedTracking parsed = parser.parse(snapshot(body, null, List.of()), "MSDU7172233");
        assertThat(parsed.etas()).isEmpty();
    }

    @Test
    void matchesAPortNameOnlyAsAWholeWord() {
        String body = """
                Container MSDU7172233
                Status: Loaded on vessel
                Port of Discharge: Vancouver, CA
                ETA at Vancouverville terminal: 2026-12-06
                """;
        ParsedTracking parsed = parser.parse(snapshot(body, null, List.of()), "MSDU7172233");
        assertThat(parsed.etas()).isEmpty();
    }

    @Test
    void refusesAResultThatBelongsToAnotherContainer() {
        String body = """
                Container MSKU1234567
                Status: Container picked up
                """;
        assertThatThrownBy(() -> parser.parse(snapshot(body, null, List.of()), "MSDU7172233"))
                .isInstanceOf(MscParseException.class)
                .hasMessageContaining("another container");
    }

    @Test
    void failsWhenNoStatusWasRendered() {
        assertThatThrownBy(() -> parser.parse(snapshot("Container MSDU7172233\n", null, List.of()), "MSDU7172233"))
                .isInstanceOf(MscParseException.class)
                .hasMessageContaining("no status text");
    }

    @Test
    void refusesWhenNoResultBlockIsVisible() {
        MscPageSnapshot page = new MscPageSnapshot(
                "Container Tracking", "https://www.msc.com/track",
                "Container MSDU7172233\nLoading...", null, List.of(), false);
        assertThatThrownBy(() -> parser.parse(page, "MSDU7172233"))
                .isInstanceOf(MscParseException.class)
                .hasMessageContaining("no visible result block");
    }

    @Test
    void refusesAResultBlockWithoutAContainerNumber() {
        String block = "Status: Gate in";
        assertThatThrownBy(() -> parser.parse(snapshotWithBlock("", block), "MSDU7172233"))
                .isInstanceOf(MscParseException.class)
                .hasMessageContaining("does not identify a container");
    }

    @Test
    void refusesAResultBlockShowingTwoContainers() {
        String block = """
                Container MSDU7172233
                Container MSKU1234567
                Status: Loaded on vessel
                """;
        assertThatThrownBy(() -> parser.parse(snapshotWithBlock(block, block), "MSDU7172233"))
                .isInstanceOf(MscParseException.class)
                .hasMessageContaining("more than one container");
    }

    @Test
    void refusesWhenThePageNamesTheContainerButTheBlockBelongsToAnother() {
        // Regression: scanning the whole page saw MSDU7172233 and accepted a
        // block that actually showed MAEU1234567 as picked up.
        String body = """
                Container MSDU7172233
                Status: In transit
                """;
        String block = """
                Container MAEU1234567
                Status: Container picked up
                """;
        assertThatThrownBy(() -> parser.parse(snapshotWithBlock(body, block), "MSDU7172233"))
                .isInstanceOf(MscParseException.class)
                .hasMessageContaining("another container");
    }

    @Test
    void parsesOnlyTheBlockThatNamesTheQueriedContainer() {
        String body = """
                Previous search: MSKU1234567 (Container picked up)
                Current result:
                Container MSDU7172233
                Status: Loaded on vessel
                """;
        String block = """
                Container MSDU7172233
                Status: Loaded on vessel
                """;
        ParsedTracking parsed = parser.parse(snapshotWithBlock(body, block), "MSDU7172233");
        assertThat(parsed.rawStatus()).isEqualTo("Loaded on vessel");
    }

    @Test
    void excludesADepartureThatLooksLikeAnArrivalEta() {
        String body = """
                Container MSDU7172233
                Status: Loaded on vessel
                Port of Discharge: Vancouver, CA
                Scheduled departure from Vancouver on 2026-12-06
                """;
        assertThat(parser.parse(snapshot(body, null, List.of()), "MSDU7172233").etas()).isEmpty();
    }

    @Test
    void excludesAPlannedLoadingFromThePodEta() {
        String body = """
                Container MSDU7172233
                Status: Loaded on vessel
                Port of Discharge: Vancouver, CA
                Scheduled loading at Vancouver on 2026-12-06
                """;
        assertThat(parser.parse(snapshot(body, null, List.of()), "MSDU7172233").etas()).isEmpty();
    }

    @Test
    void excludesAPlannedReturnFromThePodEta() {
        String body = """
                Container MSDU7172233
                Status: Discharged from vessel
                Port of Discharge: Vancouver, CA
                Scheduled return at Vancouver on 2026-12-06
                """;
        assertThat(parser.parse(snapshot(body, null, List.of()), "MSDU7172233").etas()).isEmpty();
    }

    @Test
    void keepsTheArrivalWhenANonArrivalOperationSharesTheBlock() {
        String body = """
                Container MSDU7172233
                Status: Loaded on vessel
                Port of Discharge: Vancouver, CA
                Scheduled loading at Shanghai on 2026-12-01
                Estimated arrival at POD: Dec 06, 2026
                """;
        ParsedTracking parsed = parser.parse(snapshot(body, null, List.of()), "MSDU7172233");
        assertThat(parsed.etas()).hasSize(1);
        assertThat(parsed.etas().getFirst().milestone()).isEqualTo(TrackedMilestone.POD_ARRIVAL);
        assertThat(parsed.etas().getFirst().date()).isEqualTo(LocalDate.of(2026, 12, 6));
    }

    @Test
    void refusesABlockWithTwoConflictingStatusLines() {
        String block = """
                Container MSDU7172233
                Status: Loaded on vessel
                Status: Container picked up
                """;
        assertThatThrownBy(() -> parser.parse(snapshotWithBlock(block, block), "MSDU7172233"))
                .isInstanceOf(MscParseException.class)
                .hasMessageContaining("conflicting status");
    }

    @Test
    void refusesAVisibleBlockThatCarriesConflictingValues() {
        MscPageSnapshot page = new MscPageSnapshot(
                "Container Tracking", "https://www.msc.com/track",
                "Container MSDU7172233", "Loaded on vessel", List.of(), false,
                "Container MSDU7172233\nStatus: Loaded on vessel", true);
        assertThatThrownBy(() -> parser.parse(page, "MSDU7172233"))
                .isInstanceOf(MscParseException.class)
                .hasMessageContaining("conflicting values");
    }

    @Test
    void excludesACancelledEta() {
        String body = """
                Container MSDU7172233
                Status: Loaded on vessel
                Port of Discharge: Vancouver, CA
                POD ETA cancelled: 2026-12-06
                """;
        assertThat(parser.parse(snapshot(body, null, List.of()), "MSDU7172233").etas()).isEmpty();
    }

    @Test
    void dropsALineThatMixesAnActualDateWithAnEstimate() {
        String body = """
                Container MSDU7172233
                Status: Departed
                Port of Discharge: Vancouver, CA
                Arrived at Vancouver 2026-11-20, ETA POD 2026-12-06
                """;
        assertThat(parser.parse(snapshot(body, null, List.of()), "MSDU7172233").etas()).isEmpty();
    }

    private static MscPageSnapshot snapshot(String body, String statusText, List<String> eventLines) {
        return snapshotWithBlock(body, body, statusText, eventLines);
    }

    private static MscPageSnapshot snapshotWithBlock(String body, String block) {
        return snapshotWithBlock(body, block, null, List.of());
    }

    private static MscPageSnapshot snapshotWithBlock(String body, String block, String statusText,
                                                     List<String> eventLines) {
        return new MscPageSnapshot("Container Tracking", "https://www.msc.com/track", body, statusText,
                eventLines, false, block);
    }
}
