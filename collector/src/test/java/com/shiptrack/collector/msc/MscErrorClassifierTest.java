package com.shiptrack.collector.msc;

import com.shiptrack.contract.internal.AgentErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MscErrorClassifierTest {

    private final MscErrorClassifier classifier = new MscErrorClassifier(new MscProperties());

    @Test
    void captchaWinsOverEverythingElse() {
        MscPageSnapshot snapshot = page("Please verify you are human. Too many requests.", false);
        assertThat(classifier.classify(snapshot)).contains(AgentErrorCode.CAPTCHA_REQUIRED);
    }

    @Test
    void passwordFieldMeansForcedLogin() {
        MscPageSnapshot snapshot = page("Sign in to continue", true);
        assertThat(classifier.classify(snapshot)).contains(AgentErrorCode.LOGIN_REQUIRED);
    }

    @Test
    void rateLimitAndNotFoundAreDistinct() {
        assertThat(classifier.classify(page("Too many requests, try again later", false)))
                .contains(AgentErrorCode.RATE_LIMITED);
        assertThat(classifier.classify(page("No results found for this container", false)))
                .contains(AgentErrorCode.CONTAINER_NOT_FOUND);
    }

    @Test
    void anEdgeAccessDenialIsASourceFailureNotAParseError() {
        MscPageSnapshot snapshot = new MscPageSnapshot(
                "Access Denied", "https://www.msc.com/en/track-a-shipment",
                "Access Denied You don't have permission to access this server.",
                null, List.of(), false);
        assertThat(classifier.classify(snapshot))
                .contains(AgentErrorCode.SOURCE_UNAVAILABLE);
    }

    @Test
    void aNormalResultPageIsNotAnError() {
        MscPageSnapshot snapshot = page("Status: Loaded on vessel CAPE SOUNIO", false);
        assertThat(classifier.classify(snapshot)).isEmpty();
    }

    private static MscPageSnapshot page(String body, boolean passwordField) {
        return new MscPageSnapshot("Container Tracking", "https://www.msc.com/track",
                body, null, List.of(), passwordField);
    }
}
