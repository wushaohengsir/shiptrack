package com.shiptrack.collector.msc;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.shiptrack.collector.browser.BrowserSession;
import com.shiptrack.collector.browser.BrowserSessionFactory;
import com.shiptrack.contract.internal.AgentErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Drives the public MSC tracking form in one isolated browser context and
 * captures what the result view shows.
 *
 * <p>One attempt has a hard 30-second budget for the whole query. The budget is
 * applied to navigation, form actions and the result wait; an HTTP 429/5xx, a
 * captcha, a forced login or a changed selector is classified before it can
 * masquerade as {@code TIMEOUT}. It performs exactly one attempt; retries,
 * mapping and reporting are the caller's responsibility.</p>
 */
@Component
public class MscProbe implements MscTrackingClient {

    private static final Logger log = LoggerFactory.getLogger(MscProbe.class);

    private final BrowserSessionFactory sessionFactory;
    private final MscProperties properties;
    private final MscResultParser parser;
    private final MscErrorClassifier errorClassifier;
    private final QueryCircuitBreaker circuitBreaker;

    /** Earliest time the next carrier request may start, in epoch millis. */
    private long nextQueryAtMillis;

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
        throttle();
        long deadline = System.nanoTime() + properties.getQueryTimeoutMs() * 1_000_000L;
        try (BrowserSession session = sessionFactory.open()) {
            Page page = session.page();

            Response navigation;
            try {
                navigation = page.navigate(properties.getBaseUrl(),
                        new Page.NavigateOptions().setTimeout(remainingMs(deadline)));
            } catch (TimeoutError e) {
                circuitBreaker.recordFailure();
                return new TrackingOutcome.Failure(AgentErrorCode.TIMEOUT,
                        "MSC navigation did not finish within " + properties.getQueryTimeoutMs() + "ms");
            }
            if (navigation != null) {
                int status = navigation.status();
                AgentErrorCode statusError = statusError(status);
                if (statusError != null) {
                    if (status == 429) {
                        deferAfterRateLimit(navigation);
                    }
                    circuitBreaker.recordFailure();
                    return new TrackingOutcome.Failure(statusError,
                            "MSC page returned HTTP " + status);
                }
            }

            // Classify the landing page before touching the form: a captcha or a
            // forced login must stop here, not burn the remaining budget.
            MscPageSnapshot landing = capture(page);
            Optional<AgentErrorCode> landingError = errorClassifier.classify(landing);
            if (landingError.isPresent()) {
                return signalFailure(landingError.get(), landing);
            }

            Locator input;
            try {
                page.waitForSelector(properties.getContainerInputSelector(),
                        new Page.WaitForSelectorOptions().setTimeout(formWaitMs(deadline)));
                input = page.locator(properties.getContainerInputSelector()).first();
                input.fill(containerNo, new Locator.FillOptions().setTimeout(remainingMs(deadline)));
                page.locator(properties.getSubmitSelector()).first()
                        .click(new Locator.ClickOptions().setTimeout(remainingMs(deadline)));
            } catch (TimeoutError e) {
                circuitBreaker.recordFailure();
                return new TrackingOutcome.Failure(AgentErrorCode.PARSE_ERROR,
                        "MSC tracking form was not found; the page structure likely changed");
            }

            boolean resultVisible = waitForResult(page, deadline);
            MscPageSnapshot snapshot = capture(page);

            Optional<AgentErrorCode> error = errorClassifier.classify(snapshot);
            if (error.isPresent()) {
                return signalFailure(error.get(), snapshot);
            }
            try {
                ParsedTracking parsed = parser.parse(snapshot, containerNo);
                circuitBreaker.recordSuccess();
                return new TrackingOutcome.Success(parsed, snapshot);
            } catch (MscParseException e) {
                circuitBreaker.recordFailure();
                return new TrackingOutcome.Failure(resolveUnparsable(snapshot, containerNo, resultVisible),
                        e.getMessage());
            }
        } catch (DeadlineExceeded e) {
            circuitBreaker.recordFailure();
            return new TrackingOutcome.Failure(AgentErrorCode.TIMEOUT,
                    "MSC query exceeded the " + properties.getQueryTimeoutMs() + "ms budget");
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

    static AgentErrorCode statusError(int status) {
        if (status == 429) {
            return AgentErrorCode.RATE_LIMITED;
        }
        if (status >= 500) {
            return AgentErrorCode.SOURCE_UNAVAILABLE;
        }
        return null;
    }

    private TrackingOutcome signalFailure(AgentErrorCode code, MscPageSnapshot snapshot) {
        if (code != AgentErrorCode.CONTAINER_NOT_FOUND) {
            circuitBreaker.recordFailure();
        }
        return new TrackingOutcome.Failure(code, describe(code, snapshot));
    }

    /**
     * Distinguishes a changed page structure from a genuinely slow result:
     * when the page already shows this container number, the selector is what
     * broke, so it is a parse error. Otherwise the result never arrived.
     */
    static AgentErrorCode resolveUnparsable(MscPageSnapshot snapshot, String containerNo,
                                            boolean resultVisible) {
        if (resultVisible) {
            return AgentErrorCode.PARSE_ERROR;
        }
        String body = snapshot.bodyText() == null ? "" : snapshot.bodyText().toLowerCase(Locale.ROOT);
        if (body.contains(containerNo.toLowerCase(Locale.ROOT))) {
            return AgentErrorCode.PARSE_ERROR;
        }
        return AgentErrorCode.TIMEOUT;
    }

    private boolean waitForResult(Page page, long deadline) {
        try {
            page.waitForSelector(properties.getResultSelector(),
                    new Page.WaitForSelectorOptions()
                            .setTimeout(Math.min(remainingMs(deadline), properties.getResultWaitMs())));
            return true;
        } catch (TimeoutError e) {
            return false;
        }
        // Any other PlaywrightException is a real browser failure and must not
        // be swallowed as "the result never appeared"; it reaches the caller's
        // catch block and is reported as SOURCE_UNAVAILABLE.
    }

    /**
     * Enforces a 500–1500 ms gap between carrier requests by default, and
     * honours {@code Retry-After} after a 429 so the next query backs off.
     */
    private synchronized void throttle() {
        long now = System.currentTimeMillis();
        long wait = nextQueryAtMillis - now;
        if (wait > 0) {
            sleep(wait);
        }
        long jitter = properties.getQueryJitterMs() <= 0
                ? 0
                : ThreadLocalRandom.current().nextLong(properties.getQueryJitterMs() + 1L);
        nextQueryAtMillis = System.currentTimeMillis() + properties.getMinQueryIntervalMs() + jitter;
    }

    private synchronized void deferAfterRateLimit(Response response) {
        long delay = properties.getRateLimitBackoffMs();
        try {
            String retryAfter = response.headers().get("retry-after");
            if (retryAfter != null) {
                delay = Math.max(delay, Long.parseLong(retryAfter.trim()) * 1_000L);
            }
        } catch (RuntimeException ignored) {
            // An unparseable header falls back to the configured backoff.
        }
        nextQueryAtMillis = Math.max(nextQueryAtMillis, System.currentTimeMillis() + delay);
    }

    private int formWaitMs(long deadline) {
        return Math.min(remainingMs(deadline), properties.getFormWaitMs());
    }

    /** Remaining budget in milliseconds; throws when the budget is spent. */
    private static int remainingMs(long deadline) {
        long remaining = (deadline - System.nanoTime()) / 1_000_000L;
        if (remaining <= 0) {
            throw new DeadlineExceeded();
        }
        return (int) Math.min(Integer.MAX_VALUE, remaining);
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

    private static void sleep(long millis) {
        try {
            Thread.sleep(Math.max(0, millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("collector interrupted while throttling", e);
        }
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

    /** Internal signal that the per-query budget is spent. */
    private static final class DeadlineExceeded extends RuntimeException {
        private DeadlineExceeded() {
            super(null, null, false, false);
        }
    }
}
