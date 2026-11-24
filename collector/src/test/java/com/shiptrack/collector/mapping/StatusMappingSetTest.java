package com.shiptrack.collector.mapping;

import com.shiptrack.contract.enums.TransportStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StatusMappingSetTest {

    private final StatusMappingSet mappings = new StatusMappingSet(3, List.of(
            new StatusMapping("MSC", "Loaded on vessel", TransportStatus.LOADED, 100),
            new StatusMapping("MSC", "Loaded on vessel CAPE", TransportStatus.DEPARTED, 100),
            new StatusMapping("MSC", "Gate in", TransportStatus.GATE_IN, 90),
            new StatusMapping("OTHER", "Loaded on vessel", TransportStatus.UNKNOWN, 200)));

    @Test
    void matchesCaseAndWhitespaceInsensitively() {
        assertThat(mappings.normalize("MSC", "  LOADED   on\u00a0vessel  "))
                .isEqualTo(TransportStatus.LOADED);
    }

    @Test
    void longestMatchWinsOnEqualPriority() {
        assertThat(mappings.normalize("MSC", "Loaded on vessel CAPE SOUNIO"))
                .isEqualTo(TransportStatus.DEPARTED);
    }

    @Test
    void ignoresMappingsForAnotherCarrier() {
        assertThat(mappings.normalize("MSC", "Loaded on vessel")).isEqualTo(TransportStatus.LOADED);
    }

    @Test
    void unmatchedTextStaysUnknown() {
        assertThat(mappings.normalize("MSC", "Something new the carrier invented"))
                .isEqualTo(TransportStatus.UNKNOWN);
        assertThat(mappings.normalize("MSC", null)).isEqualTo(TransportStatus.UNKNOWN);
        assertThat(mappings.normalize("MSC", "   ")).isEqualTo(TransportStatus.UNKNOWN);
    }

    @Test
    void rejectsNonPositiveVersion() {
        assertThatThrownBy(() -> new StatusMappingSet(0, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }
}
