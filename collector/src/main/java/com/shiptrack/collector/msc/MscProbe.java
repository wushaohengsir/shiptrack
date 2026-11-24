package com.shiptrack.collector.msc;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.shiptrack.collector.browser.BrowserSession;
import com.shiptrack.collector.browser.BrowserSessionFactory;
import com.shiptrack.contract.internal.AgentErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Drives the public MSC tracking form in one isolated browser context and
 * captures what the result view shows. It performs exactly one attempt;
 * retries, mapping and reporting are the caller's responsibility.
 */
@Component
public class MscProbe implements MscTrackingClient {

    private static final Logger log = LoggerFactory.getLogger(MscProbe.class);

    private final BrowserSessionFactory sessionFactory;
    private final MscProperties properties;
    private final MscResultParser parser;
    private final MscErrorClassifier errorClassifier;
    private final QueryCircuitBreaker circuitBreaker;

    public MscProbe(BrowserSessionFactory sessionFactory,
                    MscProperties properties,
                    MscResultParser parser,
                    MscErrorClassifier errorClassifier,
                    QueryCircuitBreaker circuitBreaker) {
        this.sessionFactory = sessionFactory;
        this.properties = properties;
        this.parser = parser;
        this.errorClassifier = errorClassifier;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public TrackingOutcome query(String containerNo) {
        if (containerNo == null || containerNo.isBlank()) {
            return new TrackingOutcome.Failure(AgentErrorCode.CONTAINER_NOT_FOUND, "blank container number");
        }
        if (!circuitBreaker.allow()) {
            return new TrackingOutcome.Failure(AgentErrorCode.SOURCE_UNAVAILABLE,
                    "circuit breaker open after repeated carrier failures");
        }
        try (BrowserSession session = sessionFactory.open()) {
            Page page = session.page();
            page.navigate(properties.getBaseUrl(),
                    new Page.NavigateOptions().setTimeout(properties.getQueryTimeoutMs()));

            Locator input = page.locator(properties.getContainerInputSelector()).first();
            input.fill(containerNo);
            page.locator(properties.getSubmitSelector()).first().click();

            boolean resultVisible = waitForResult(page);
            MscPageSnapshot snapshot = capture(page);

            Optional<AgentErrorCode> error = errorClassifier.classify(snapshot);
            if (error.isPresent()) {
                AgentErrorCode code = error.get();
                if (code != AgentErrorCode.CONTAINER_NOT_FOUND) {
                    circuitBreaker.recordFailure();
                }
                return new TrackingOutcome.Failure(code, describe(code, snapshot));
            }
            try {
                ParsedTracking parsed = parser.parse(snapshot, containerNo);
                circuitBreaker.recordSuccess();
                return new TrackingOutcome.Success(parsed, snapshot);
            } catch (MscParseException e) {
                circuitBreaker.recordFailure();
                if (!resultVisible) {
                    // The result container never rendered and no status was readable:
                    // report the timeout, not a parse error.
                    return new TrackingOutcome.Failure(AgentErrorCode.TIMEOUT,
                            "MSC result view did not appear within " + properties.getResultWaitMs() + "ms");
                }
                return new TrackingOutcome.Failure(AgentErrorCode.PARSE_ERROR, e.getMessage());
            }
        } catch (TimeoutError e) {
            circuitBreaker.recordFailure();
            return new TrackingOutcome.Failure(AgentErrorCode.TIMEOUT,
                    "MSC page timeout: " + firstLine(e.getMessage()));
        } catch (PlaywrightException e) {
            circuitBreaker.recordFailure();
            log.warn("Browser failure while querying {}: {}", containerNo, firstLine(e.getMessage()));
            return new TrackingOutcome.Failure(AgentErrorCode.SOURCE_UNAVAILABLE,
                    "browser error: " + firstLine(e.getMessage()));
        }
    }

    private boolean waitForResult(Page page) {
        try {
            page.waitForSelector(properties.getResultSelector(),
                    new Page.WaitForSelectorOptions().setTimeout(properties.getResultWaitMs()));
            return true;
        } catch (TimeoutError e) {
            return false;
        } catch (PlaywrightException e) {
            return false;
        }
    }

    private MscPageSnapshot capture(Page page) {
        String title = safe(page::title);
        String url = safe(page::url);
        String body = safe(() -> page.locator("body").innerText());
        if (body != null && body.length() > properties.getMaxBodyChars()) {
            body = body.substring(0, properties.getMaxBodyChars());
        }

        String statusText = null;
        try {
            Locator status = page.locator(properties.getStatusSelector());
            if (status.count() > 0) {
                statusText = status.first().innerText();
            }
        } catch (PlaywrightException ignored) {
            // Optional field: absence is reported by the parser, not here.
        }

        List<String> eventLines = new ArrayList<>();
        try {
            Locator rows = page.locator(properties.getEventRowSelector());
            int count = Math.min(rows.count(), 50);
            for (int i = 0; i < count; i++) {
                eventLines.add(rows.nth(i).innerText());
            }
        } catch (PlaywrightException ignored) {
            // Optional: the timeline may be absent on a "no result" page.
        }

        boolean passwordField = false;
        try {
            passwordField = page.locator(properties.getLoginFieldSelector()).count() > 0;
        } catch (PlaywrightException ignored) {
            // Optional signal.
        }

        return new MscPageSnapshot(title, url, body, statusText, eventLines, passwordField);
    }

    private static String describe(AgentErrorCode code, MscPageSnapshot snapshot) {
        String title = snapshot.title() == null ? "" : snapshot.title();
        return switch (code) {
            case CONTAINER_NOT_FOUND -> "carrier page reports no tracking information";
            case CAPTCHA_REQUIRED -> "carrier page requires a captcha";
            case LOGIN_REQUIRED -> "carrier page requires a login";
            case RATE_LIMITED -> "carrier page rate limited the query";
            default -> "unclassified carrier page: " + title;
        };
    }

    private static String firstLine(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        int newline = message.indexOf('\n');
        String line = newline < 0 ? message : message.substring(0, newline);
        return line.length() > 300 ? line.substring(0, 300) : line;
    }

    private static String safe(SupplierWithException supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    @FunctionalInterface
    private interface SupplierWithException {
        String get();
    }
}
