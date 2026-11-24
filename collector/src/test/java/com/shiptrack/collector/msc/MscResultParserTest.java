package com.shiptrack.collector.msc;

import com.shiptrack.contract.enums.TrackedMilestone;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
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
            Gateway ETA: 06 Dec 2026 14:30 EST
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

        assertThat(parsed.etas()).hasSize(2);
        RawEta dateOnly = parsed.etas().get(0);
        assertThat(dateOnly.milestone()).isEqualTo(TrackedMilestone.POD_ARRIVAL);
        assertThat(dateOnly.date()).isEqualTo(LocalDate.of(2026, 12, 6));
        assertThat(dateOnly.time()).isNull();
        assertThat(dateOnly.timezone()).isNull();

        RawEta withTime = parsed.etas().get(1);
        assertThat(withTime.date()).isEqualTo(LocalDate.of(2026, 12, 6));
        assertThat(withTime.time()).isEqualTo(LocalTime.of(14, 30));
        assertThat(withTime.timezone()).isEqualTo("America/New_York");
    }

    @Test
    void fallsBackToTheStatusLabelWhenTheSelectorFoundNothing() {
        ParsedTracking parsed = parser.parse(snapshot("Status: Gate in", null, List.of()), "MSDU7172233");
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
    void failsWhenNoStatusWasRendered() {
        assertThatThrownBy(() -> parser.parse(snapshot("Container MSDU7172233\n", null, List.of()), "MSDU7172233"))
                .isInstanceOf(MscParseException.class)
                .hasMessageContaining("no status text");
    }

    private static MscPageSnapshot snapshot(String body, String statusText, List<String> eventLines) {
        return new MscPageSnapshot("Container Tracking", "https://www.msc.com/track", body, statusText,
                eventLines, false);
    }
}
