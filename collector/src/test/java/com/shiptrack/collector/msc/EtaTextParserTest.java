package com.shiptrack.collector.msc;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class EtaTextParserTest {

    @Test
    void parsesTheFormatsTheCarrierPageUses() {
        assertThat(EtaTextParser.parseDate("Estimated arrival: Dec 06, 2026"))
                .contains(LocalDate.of(2026, 12, 6));
        assertThat(EtaTextParser.parseDate("ETA 6 December 2026")).contains(LocalDate.of(2026, 12, 6));
        assertThat(EtaTextParser.parseDate("Arrival 2026-12-06")).contains(LocalDate.of(2026, 12, 6));
        assertThat(EtaTextParser.parseDate("06 Dec. 2026")).contains(LocalDate.of(2026, 12, 6));
    }

    @Test
    void refusesToGuessAnUnrecognisedDate() {
        assertThat(EtaTextParser.parseDate("sometime next week")).isEmpty();
        assertThat(EtaTextParser.parseDate("32/13/2026")).isEmpty();
        assertThat(EtaTextParser.parseDate(null)).isEmpty();
    }

    @Test
    void usesTimeOnlyWithAnExplicitZone() {
        assertThat(EtaTextParser.parseTime("Dec 06, 2026 14:30 EST"))
                .hasValueSatisfying(t -> {
                    assertThat(t.time().getHour()).isEqualTo(14);
                    assertThat(t.time().getMinute()).isEqualTo(30);
                    assertThat(t.timezone()).isEqualTo("America/New_York");
                });
        assertThat(EtaTextParser.parseTime("Dec 06, 2026 14:30")).isEmpty();
        assertThat(EtaTextParser.parseTime("Dec 06, 2026 14:30 CST")).isEmpty();
    }
}
