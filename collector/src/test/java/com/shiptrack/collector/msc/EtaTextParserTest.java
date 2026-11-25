package com.shiptrack.collector.msc;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

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

    @Test
    void appliesAnExplicitUtcOffsetInsteadOfDroppingIt() {
        OffsetDateTime plus8 = EtaTextParser.parseDateTime("2026-12-06 14:30 UTC+08:00").orElseThrow();
        assertThat(plus8.toInstant()).isEqualTo(Instant.parse("2026-12-06T06:30:00Z"));
        assertThat(plus8.getOffset()).isEqualTo(ZoneOffset.ofHours(8));
        assertThat(EtaTextParser.parseTime("2026-12-06 14:30 UTC+08:00").orElseThrow().timezone())
                .isEqualTo("Etc/GMT-8");

        OffsetDateTime minus5 = EtaTextParser.parseDateTime("2026-12-06 09:30 -05:00").orElseThrow();
        assertThat(minus5.toInstant()).isEqualTo(Instant.parse("2026-12-06T14:30:00Z"));
        assertThat(EtaTextParser.parseTime("2026-12-06 09:30 -05:00").orElseThrow().timezone())
                .isEqualTo("Etc/GMT+5");
    }

    @Test
    void parsesIsoTimestampsWithAZone() {
        OffsetDateTime iso = EtaTextParser.parseDateTime("2026-12-06T14:30:00+08:00").orElseThrow();
        assertThat(iso.toInstant()).isEqualTo(Instant.parse("2026-12-06T06:30:00Z"));
        assertThat(EtaTextParser.parseTime("2026-12-06T14:30:00+08:00").orElseThrow().timezone())
                .isEqualTo("Etc/GMT-8");

        OffsetDateTime zulu = EtaTextParser.parseDateTime("2026-12-06T14:30:00Z").orElseThrow();
        assertThat(zulu.toInstant()).isEqualTo(Instant.parse("2026-12-06T14:30:00Z"));
        assertThat(EtaTextParser.parseTime("2026-12-06T14:30:00Z").orElseThrow().timezone())
                .isEqualTo("UTC");
    }

    @Test
    void keepsTheSecondsThePagePrinted() {
        EtaTextParser.ZonedTime time =
                EtaTextParser.parseTime("2026-12-06T14:30:57+08:00").orElseThrow();
        assertThat(time.time()).isEqualTo(java.time.LocalTime.of(14, 30, 57));

        OffsetDateTime parsed = EtaTextParser.parseDateTime("2026-12-06T14:30:57+08:00").orElseThrow();
        assertThat(parsed.toInstant()).isEqualTo(Instant.parse("2026-12-06T06:30:57Z"));
    }

    @Test
    void countsDatesSoAmbiguousLinesCanBeDropped() {
        assertThat(EtaTextParser.dateOccurrences("Estimated arrival at POD: Dec 06, 2026")).isEqualTo(1);
        assertThat(EtaTextParser.dateOccurrences(
                "Arrived 2026-11-20, ETA POD 2026-12-06")).isEqualTo(2);
        assertThat(EtaTextParser.dateOccurrences("no date here")).isZero();
    }

    @Test
    void readsTheSitesNumericDatesDayFirst() {
        // Live MSC page, 2026-11-25: POD ETA 07/12/2026 = 7 December.
        assertThat(EtaTextParser.parseDate("POD ETA 07/12/2026"))
                .contains(LocalDate.of(2026, 12, 7));
        assertThat(EtaTextParser.parseDate("21/11/2026 Busan, KR Full Transshipment Loaded"))
                .contains(LocalDate.of(2026, 11, 21));
        // A day above 12 can only be a day, not a month.
        assertThat(EtaTextParser.parseDate("25/11/2026")).contains(LocalDate.of(2026, 11, 25));
    }

    @Test
    void resolvesDayMonthAmbiguityByTheDeclaredOrderAndNeverByGuessing() {
        // 07/12 is ambiguous (both <= 12). The site order decides: day first.
        LocalDate seventhOfDecember = EtaTextParser.parseDate("07/12/2026").orElseThrow();
        assertThat(seventhOfDecember).isEqualTo(LocalDate.of(2026, 12, 7));
        assertThat(seventhOfDecember)
                .as("must not be silently read as MM/dd")
                .isNotEqualTo(LocalDate.of(2026, 7, 12));

        // Same digits, reversed: still day-first, so this is 12 July.
        assertThat(EtaTextParser.parseDate("12/07/2026")).contains(LocalDate.of(2026, 7, 12));

        // Impossible day-first (month 25): dropped, not re-read as MM/dd.
        assertThat(EtaTextParser.parseDate("12/25/2026")).isEmpty();
        assertThat(EtaTextParser.parseDate("13/13/2026")).isEmpty();
        assertThat(EtaTextParser.parseDate("31/02/2026")).isEmpty();

        // The policy is explicit, so a month-first site can be configured
        // without changing the parser.
        assertThat(EtaTextParser.parseDate("12/25/2026", EtaTextParser.DateOrder.MONTH_FIRST))
                .contains(LocalDate.of(2026, 12, 25));
        assertThat(EtaTextParser.parseDate("07/12/2026", EtaTextParser.DateOrder.MONTH_FIRST))
                .contains(LocalDate.of(2026, 7, 12));
    }

    @Test
    void countsNumericDatesForTheAmbiguityGuard() {
        assertThat(EtaTextParser.dateOccurrences("07/12/2026 Vancouver Estimated Time of Arrival"))
                .isEqualTo(1);
        assertThat(EtaTextParser.dateOccurrences("07/12/2026 and 21/11/2026")).isEqualTo(2);
    }

    @Test
    void keepsAHalfHourOffsetAccurateEvenThoughItCannotBeNamedInIanaForm() {
        OffsetDateTime offset = EtaTextParser.parseDateTime("2026-12-06 14:30 UTC+05:30").orElseThrow();
        assertThat(offset.toInstant()).isEqualTo(Instant.parse("2026-12-06T09:00:00Z"));
        // The reporter later downgrades this to DATE precision because §3.3
        // only allows IANA names, but the offset itself must be right.
        assertThat(EtaTextParser.parseTime("2026-12-06 14:30 UTC+05:30").orElseThrow().timezone())
                .isEqualTo("+05:30");
    }
}
