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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Drives the public MSC tracking form in one isolated browser context and
 * captures what the result view shows.
 *
 * <p>One attempt has a hard 30-second budget for the whole query. Throttling,
 * browser startup, navigation, form actions, the result wait and DOM capture
 * all share that one absolute deadline (and the caller may pass a shorter one
 * from the claim lease). A 429/5xx from either the page or the tracking request,
 * a captcha, a forced login or a changed selector is classified before it can
 * masquerade as {@code TIMEOUT}. It performs exactly one attempt; retries,
 * mapping and reporting are the caller's responsibility.</p>
 */
@Component
public class MscProbe implements MscTrackingClient {

    private static final Logger log = LoggerFactory.getLogger(MscProbe.class);
    private static final long POLL_INTERVAL_MS = 200;

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
        return query(containerNo, properties.getQueryTimeoutMs());
    }

    @Override
    public TrackingOutcome query(String containerNo, long budgetMs) {
        if (containerNo == null || containerNo.isBlank()) {
            return new TrackingOutcome.Failure(AgentErrorCode.CONTAINER_NOT_FOUND, "blank container number");
        }
        if (!circuitBreaker.allow()) {
            return new TrackingOutcome.Failure(AgentErrorCode.SOURCE_UNAVAILABLE,
                    "circuit breaker open after repeated carrier failures");
        }
        long budget = Math.min(properties.getQueryTimeoutMs(), Math.max(0, budgetMs));
        if (budget <= 0) {
            return new TrackingOutcome.Failure(AgentErrorCode.TIMEOUT,
                    "no lease time left for a carrier query");
        }
        long deadline = System.nanoTime() + budget * 1_000_000L;
        try {
            throttle(deadline);
            try (BrowserSession session = sessionFactory.open()) {
                if (remainingMsOrZero(deadline) <= 0) {
                    return timeout("browser startup used the whole query budget");
                }
                return queryWithSession(session.page(), containerNo, deadline);
            }
        } catch (DeadlineExceeded e) {
            circuitBreaker.recordFailure();
            return timeout("MSC query exceeded its " + budget + "ms budget");
        } catch (TimeoutError e) {
            circuitBreaker.recordFailure();
            return timeout("MSC page timeout: " + firstLine(e.getMessage()));
        } catch (PlaywrightException e) {
            circuitBreaker.recordFailure();
            log.warn("Browser failure while querying {}: {}", containerNo, firstLine(e.getMessage()));
            return new TrackingOutcome.Failure(AgentErrorCode.SOURCE_UNAVAILABLE,
                    "browser error: " + firstLine(e.getMessage()));
        }
    }

    private TrackingOutcome queryWithSession(Page page, String containerNo, long deadline) {
        Response navigation;
        try {
            navigation = page.navigate(properties.getBaseUrl(),
                    new Page.NavigateOptions().setTimeout(remainingMs(deadline)));
        } catch (TimeoutError e) {
            return timeout("MSC navigation did not finish within the query budget");
        }
        if (navigation != null) {
            int status = navigation.status();
            AgentErrorCode statusError = statusError(status);
            if (statusError != null) {
                if (status == 429) {
                    deferAfterRateLimit(navigation);
                }
                circuitBreaker.recordFailure();
                return new TrackingOutcome.Failure(statusError, "MSC page returned HTTP " + status);
            }
        }

        // Classify the landing page before touching the form: a captcha or a
        // forced login must stop here, not burn the remaining budget.
        MscPageSnapshot landing = capture(page, deadline, true);
        Optional<AgentErrorCode> landingError = errorClassifier.classify(landing);
        if (landingError.isPresent()) {
            return signalFailure(landingError.get(), landing);
        }

        Locator input;
        try {
            page.waitForSelector(properties.getContainerInputSelector(),
                    new Page.WaitForSelectorOptions().setTimeout(formWaitMs(deadline)));
            input = page.locator(properties.getContainerInputSelector()).first();
        } catch (TimeoutError e) {
            // The form itself is missing: that is selector drift, unless the
            // whole budget is already gone.
            return formFailure(deadline, "MSC tracking form was not found; the page structure likely changed");
        }

        AtomicReference<SubmitResponse> submitResponse = new AtomicReference<>();
        // Captured before the click so a 429/5xx from the tracking request is
        // still seen when the page renders no error text at all.
        Consumer<Response> responseListener = response -> {
            SubmitResponse signal = submitSignal(response);
            if (signal != null) {
                submitResponse.compareAndSet(null, signal);
            }
        };
        page.onResponse(responseListener);
        try {
            try {
                input.fill(containerNo, new Locator.FillOptions().setTimeout(remainingMs(deadline)));
                page.locator(properties.getSubmitSelector()).first()
                        .click(new Locator.ClickOptions().setTimeout(remainingMs(deadline)));
            } catch (TimeoutError e) {
                return formFailure(deadline, "MSC tracking form could not be filled or submitted");
            }

            WaitResult wait = waitForResult(page, deadline, submitResponse);
            MscPageSnapshot snapshot = capture(page, deadline, false);

            Optional<AgentErrorCode> error = errorClassifier.classify(snapshot);
            if (error.isPresent()) {
                return signalFailure(error.get(), snapshot);
            }

            SubmitResponse response = submitResponse.get();
            if (response != null && response.status() >= 400) {
                AgentErrorCode code = statusError(response.status());
                if (code != null) {
                    if (response.status() == 429) {
                        deferAfterRateLimit(response.retryAfterSeconds());
                    }
                    circuitBreaker.recordFailure();
                    return new TrackingOutcome.Failure(code,
                            "MSC tracking request returned HTTP " + response.status());
                }
            }

            try {
                ParsedTracking parsed = parser.parse(snapshot, containerNo);
                circuitBreaker.recordSuccess();
                return new TrackingOutcome.Success(parsed, snapshot);
            } catch (MscParseException e) {
                circuitBreaker.recordFailure();
                return new TrackingOutcome.Failure(
                        resolveUnparsable(snapshot, containerNo, wait == WaitResult.RESULT),
                        e.getMessage());
            }
        } finally {
            try {
                page.offResponse(responseListener);
            } catch (PlaywrightException ignored) {
                // The page may already be closed after a browser crash.
            }
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

    private TrackingOutcome timeout(String message) {
        return new TrackingOutcome.Failure(AgentErrorCode.TIMEOUT, message);
    }

    /**
     * A form timeout means selector drift only while budget remains; once the
     * deadline is gone it is a genuine timeout.
     */
    private TrackingOutcome formFailure(long deadline, String message) {
        circuitBreaker.recordFailure();
        if (remainingMsOrZero(deadline) <= 0) {
            return timeout("MSC query exceeded its budget while " + message);
        }
        return new TrackingOutcome.Failure(AgentErrorCode.PARSE_ERROR, message);
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

    /**
     * Polls for the page's terminal state: the result block, a captcha/login
     * signal, or a 429/5xx from the tracking request. This is what lets a
     * late captcha be reported as such instead of waiting the full budget for
     * a result selector that will never appear.
     */
    private WaitResult waitForResult(Page page, long deadline, AtomicReference<SubmitResponse> submitResponse) {
        long resultDeadline = Math.min(deadline,
                System.nanoTime() + Math.max(0, properties.getResultWaitMs()) * 1_000_000L);
        while (true) {
            if (visible(page, properties.getResultSelector())) {
                return WaitResult.RESULT;
            }
            SubmitResponse response = submitResponse.get();
            if (response != null && response.status() >= 400) {
                return WaitResult.HTTP_ERROR;
            }
            if (errorClassifier.classify(capture(page, deadline, true)).isPresent()) {
                return WaitResult.SIGNAL;
            }
            long remaining = remainingMsOrZero(resultDeadline);
            if (remaining <= 0) {
                return WaitResult.PENDING;
            }
            sleep(Math.min(POLL_INTERVAL_MS, remaining));
        }
    }

    SubmitResponse submitSignal(Response response) {
        if (response == null) {
            return null;
        }
        try {
            String type = response.request().resourceType();
            if (!("xhr".equals(type) || "fetch".equals(type) || "document".equals(type))) {
                return null;
            }
            String host = hostOf(response.url());
            String baseHost = hostOf(properties.getBaseUrl());
            if (!sameDomain(host, baseHost)) {
                return null;
            }
            return new SubmitResponse(response.status(), response.headerValue("retry-after"));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * The tracking call can live on another subdomain of the carrier, so the
     * registrable domain is compared rather than the exact host. IP hosts are
     * compared exactly.
     */
    static boolean sameDomain(String host, String baseHost) {
        if (host == null) {
            return false;
        }
        if (baseHost == null || host.equalsIgnoreCase(baseHost)) {
            return true;
        }
        if (isIpv4(host) || isIpv4(baseHost)) {
            return false;
        }
        String left = registrableDomain(host);
        String right = registrableDomain(baseHost);
        return left != null && left.equalsIgnoreCase(right);
    }

    private static String registrableDomain(String host) {
        String[] parts = host.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        return parts[parts.length - 2] + "." + parts[parts.length - 1];
    }

    private static boolean isIpv4(String host) {
        return host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    /**
     * Enforces a 500–1500 ms gap between carrier requests by default, and
     * honours {@code Retry-After} after a 429 so the next query backs off. The
     * wait is taken from the query budget: if the gap is longer than the time
     * left, the attempt ends as a timeout instead of overrunning the lease.
     */
    private synchronized void throttle(long deadline) {
        long now = System.currentTimeMillis();
        long wait = nextQueryAtMillis - now;
        if (wait > 0) {
            long remaining = remainingMsOrZero(deadline);
            if (wait >= remaining) {
                throw new DeadlineExceeded();
            }
            sleep(wait);
        }
        long jitter = properties.getQueryJitterMs() <= 0
                ? 0
                : ThreadLocalRandom.current().nextLong(properties.getQueryJitterMs() + 1L);
        nextQueryAtMillis = System.currentTimeMillis() + properties.getMinQueryIntervalMs() + jitter;
    }

    private synchronized void deferAfterRateLimit(Response response) {
        deferAfterRateLimit(response.headers().get("retry-after"));
    }

    private synchronized void deferAfterRateLimit(String retryAfter) {
        long delay = properties.getRateLimitBackoffMs();
        try {
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
        long remaining = remainingMsOrZero(deadline);
        if (remaining <= 0) {
            throw new DeadlineExceeded();
        }
        return (int) Math.min(Integer.MAX_VALUE, remaining);
    }

    private static long remainingMsOrZero(long deadline) {
        return (deadline - System.nanoTime()) / 1_000_000L;
    }

    /**
     * @param light only the body, title, url and login signal; used by the poll
     *              loop so a repeated capture stays cheap
     */
    private MscPageSnapshot capture(Page page, long deadline, boolean light) {
        int timeout = Math.min(2_000, remainingMs(deadline));
        String title = safe(page::title);
        String url = safe(page::url);
        String body = safe(() -> page.locator("body")
                .innerText(new Locator.InnerTextOptions().setTimeout(timeout)));
        if (body != null && body.length() > properties.getMaxBodyChars()) {
            body = body.substring(0, properties.getMaxBodyChars());
        }

        boolean passwordField = visible(page, properties.getLoginFieldSelector());
        if (light) {
            return new MscPageSnapshot(title, url, body, null, List.of(), passwordField);
        }

        String statusText = null;
        try {
            Locator status = page.locator(properties.getStatusSelector());
            if (status.count() > 0) {
                statusText = status.first()
                        .innerText(new Locator.InnerTextOptions().setTimeout(timeout));
            }
        } catch (PlaywrightException ignored) {
            // Optional field: absence is reported by the parser, not here.
        }

        List<String> eventLines = new ArrayList<>();
        try {
            Locator rows = page.locator(properties.getEventRowSelector());
            int count = Math.min(rows.count(), 50);
            int rowTimeout = (int) Math.min(500, Math.max(1, remainingMsOrZero(deadline)));
            for (int i = 0; i < count; i++) {
                eventLines.add(rows.nth(i)
                        .innerText(new Locator.InnerTextOptions().setTimeout(rowTimeout)));
            }
        } catch (PlaywrightException ignored) {
            // Optional: the timeline may be absent on a "no result" page.
        }

        return new MscPageSnapshot(title, url, body, statusText, eventLines, passwordField);
    }

    private static boolean visible(Page page, String selector) {
        try {
            Locator locator = page.locator(selector).first();
            return locator.count() > 0 && locator.isVisible();
        } catch (PlaywrightException e) {
            return false;
        }
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

    private enum WaitResult {
        RESULT,
        SIGNAL,
        HTTP_ERROR,
        PENDING
    }

    record SubmitResponse(int status, String retryAfterSeconds) {
    }

    /** Internal signal that the per-query budget is spent. */
    private static final class DeadlineExceeded extends RuntimeException {
        private DeadlineExceeded() {
            super(null, null, false, false);
        }
    }
}
