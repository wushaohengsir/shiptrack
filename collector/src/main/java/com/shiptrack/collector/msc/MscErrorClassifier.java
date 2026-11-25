package com.shiptrack.collector.msc;

import com.shiptrack.contract.internal.AgentErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Turns page signals into the definitive error codes the API understands.
 * Order matters: a captcha page may also contain "try again later", and a
 * forced login is not a parse failure.
 */
@Component
public final class MscErrorClassifier {

    private final MscProperties properties;

    public MscErrorClassifier(MscProperties properties) {
        this.properties = properties;
    }

    public Optional<AgentErrorCode> classify(MscPageSnapshot snapshot) {
        String body = snapshot.bodyText() == null ? "" : snapshot.bodyText().toLowerCase(Locale.ROOT);
        String title = snapshot.title() == null ? "" : snapshot.title().toLowerCase(Locale.ROOT);
        String haystack = title + "\n" + body;

        if (containsAny(haystack, properties.getCaptchaTexts())) {
            return Optional.of(AgentErrorCode.CAPTCHA_REQUIRED);
        }
        if (snapshot.passwordFieldPresent()) {
            return Optional.of(AgentErrorCode.LOGIN_REQUIRED);
        }
        if (containsAny(haystack, properties.getRateLimitTexts())) {
            return Optional.of(AgentErrorCode.RATE_LIMITED);
        }
        if (containsAny(haystack, properties.getNotFoundTexts())) {
            return Optional.of(AgentErrorCode.CONTAINER_NOT_FOUND);
        }
        return Optional.empty();
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        if (needles == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank()
                    && haystack.contains(needle.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
