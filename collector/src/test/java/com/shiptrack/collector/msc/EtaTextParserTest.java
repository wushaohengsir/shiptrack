package com.shiptrack.collector.msc;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;

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
    void rejectsCalendarImpossibleDatesInsteadOfRollingThemOver() {
        // SMART would turn Feb 30 into Feb 28 and report a wrong ETA.
        assertThat(EtaTextParser.parseDate("Feb 30, 2026")).isEmpty();
        assertThat(EtaTextParser.parseDate("2026-02-30")).isEmpty();
    }

    @Test
    void usesTimeOnlyWithAnExplicitZone() {
        assertThat(EtaTextParser.parseTime("Dec 06, 2026 14:30 EST"))
                .hasValueSatisfying(t -> {
                    assertThat(t.time().getHour()).isEqualTo(14);
                    assertThat(t.time().getMinute()).isEqualTo(30);
                    assertThat(t.timezone()).isEqualTo("Etc/GMT+5");
                });
        assertThat(EtaTextParser.parseTime("Dec 06, 2026 14:30")).isEmpty();
        assertThat(EtaTextParser.parseTime("Dec 06, 2026 14:30 CST")).isEmpty();
    }

    @Test
    void treatsAbbreviationsAsFixedOffsetsNotDaylightZones() {
        OffsetDateTime pst = EtaTextParser.parseDateTime("Jul 6, 2026 16:00 PST").orElseThrow();
        // PST is exactly UTC-8 even in July, when Los Angeles is on PDT.
        assertThat(pst.toInstant()).isEqualTo(Instant.parse("2026-07-07T00:00:00Z"));
        assertThat(pst.getOffset().getTotalSeconds()).isEqualTo(-8 * 3600);

        OffsetDateTime edt = EtaTextParser.parseDateTime("Jul 6, 2026 16:00 EDT").orElseThrow();
        assertThat(edt.toInstant()).isEqualTo(Instant.parse("2026-07-06T20:00:00Z"));
    }

    @Test
    void aDateWithoutTimeOrZoneStaysDateOnly() {
        assertThat(EtaTextParser.parseDateTime("Estimated arrival: Dec 06, 2026")).isEmpty();
        assertThat(EtaTextParser.parseDateTime("Dec 06, 2026 14:30")).isEmpty();
    }
}
