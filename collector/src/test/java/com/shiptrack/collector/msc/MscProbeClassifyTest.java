package com.shiptrack.collector.msc;

import com.shiptrack.contract.internal.AgentErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MscProbeClassifyTest {

    @Test
    void mapsHttpStatusBeforeWaitingForASelector() {
        assertThat(MscProbe.statusError(401)).isEqualTo(AgentErrorCode.LOGIN_REQUIRED);
        assertThat(MscProbe.statusError(403)).isEqualTo(AgentErrorCode.SOURCE_UNAVAILABLE);
        assertThat(MscProbe.statusError(429)).isEqualTo(AgentErrorCode.RATE_LIMITED);
        assertThat(MscProbe.statusError(500)).isEqualTo(AgentErrorCode.SOURCE_UNAVAILABLE);
        assertThat(MscProbe.statusError(503)).isEqualTo(AgentErrorCode.SOURCE_UNAVAILABLE);
        assertThat(MscProbe.statusError(200)).isNull();
        assertThat(MscProbe.statusError(404)).isNull();
    }

    @Test
    void submitsWithTheTrackingFormButtonNotTheHeaderLogin() {
        MscProperties properties = new MscProperties();
        assertThat(properties.getSubmitSelector())
                .isEqualTo(".msc-flow-tracking__form button.msc-search-autocomplete__search");
        assertThat(properties.getLegacySubmitSelector())
                .isEqualTo("button[type='submit'], button:has-text('Track')");
        assertThat(properties.getConsentSelectors())
                .startsWith("#onetrust-accept-btn-handler");
    }

    @Test
    void distinguishesAChangedPageFromAGenuineTimeout() {
        MscPageSnapshot showsContainer = new MscPageSnapshot(
                "Tracking", "https://www.msc.com/track",
                "Tracking MSDU7172233 in a new layout", null, List.of(), false);

        assertThat(MscProbe.resolveUnparsable(showsContainer, "MSDU7172233", false))
                .isEqualTo(AgentErrorCode.PARSE_ERROR);
        assertThat(MscProbe.resolveUnparsable(showsContainer, "MSDU7172233", true))
                .isEqualTo(AgentErrorCode.PARSE_ERROR);

        MscPageSnapshot blank = new MscPageSnapshot(
                "Tracking", "https://www.msc.com/track", "Loading...", null, List.of(), false);
        assertThat(MscProbe.resolveUnparsable(blank, "MSDU7172233", false))
                .isEqualTo(AgentErrorCode.TIMEOUT);
    }

    @Test
    void acceptsATrackingCallOnAnotherSubdomainOfTheCarrier() {
        assertThat(MscProbe.sameDomain("api.msc.com", "www.msc.com")).isTrue();
        assertThat(MscProbe.sameDomain("www.msc.com", "www.msc.com")).isTrue();
        assertThat(MscProbe.sameDomain("tracking.example.org", "www.msc.com")).isFalse();
        assertThat(MscProbe.sameDomain("127.0.0.1", "127.0.0.1")).isTrue();
        assertThat(MscProbe.sameDomain("127.0.0.2", "127.0.0.1")).isFalse();
        assertThat(MscProbe.sameDomain(null, "www.msc.com")).isFalse();
    }

    @Test
    void refusesAQueryBudgetAboveThirtySeconds() {
        MscProperties properties = new MscProperties();
        properties.setQueryTimeoutMs(60_000);
        assertThat(properties.getQueryTimeoutMs()).isEqualTo(30_000);
    }
}
