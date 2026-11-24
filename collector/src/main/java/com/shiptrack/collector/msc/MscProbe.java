package com.shiptrack.collector.msc;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.shiptrack.collector.browser.BrowserSession;
import com.shiptrack.collector.browser.BrowserSessionFactory;
import com.shiptrack.contract.internal.AgentErrorCode;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Drives the public MSC tracking form in one isolated browser context and
 * captures what the result view shows.
 *
 * <p>One attempt has a hard 30-second budget for the whole query. Throttling,
 * browser startup, navigation, form actions, the result wait and DOM capture
 * all share that one absolute deadline (and the caller may pass a shorter one
 * from the claim lease). A 429/5xx from either the page or the tracking request
 * (including after a redirect), a captcha, a forced login or a changed selector
 * is classified before it can masquerade as {@code TIMEOUT}. It performs
 * exactly one attempt; retries, mapping and reporting are the caller's
 * responsibility.</p>
 *
 * <p>Playwright objects are not thread-safe. Browser startup, every page call
 * and session cleanup therefore run on one dedicated thread. When the caller's
 * budget runs out first, the caller returns immediately and marks the run
 * abandoned; the worker thread still closes the session when its call finally
 * returns, so a late session can never be left open.</p>
 */
@Component
public class MscProbe implements MscTrackingClient {

    private static final Logger log = LoggerFactory.getLogger(MscProbe.class);
    private static final long POLL_INTERVAL_MS = 200;
    private static final int MAX_REDIRECT_HOPS = 10;

    private final BrowserSessionFactory sessionFactory;
    private final MscProperties properties;
    private final MscResultParser parser;
    private final MscErrorClassifier errorClassifier;
    private final QueryCircuitBreaker circuitBreaker;

    /**
     * Owns Playwright: open, all page work and close happen on this one thread,
     * as the Playwright threading rules require.
     */
    private final ExecutorService browserThread;

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
        this.browserThread = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "msc-browser");
            thread.setDaemon(true);
            return thread;
        });
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
        RunToken run = new RunToken();
        Future<TrackingOutcome> pending;
        try {
            pending = browserThread.submit(() -> executeQuery(containerNo, budget, deadline, run));
        } catch (RejectedExecutionException e) {
            return new TrackingOutcome.Failure(AgentErrorCode.SOURCE_UNAVAILABLE,
                    "collector is shutting down");
        }
        try {
            long wait = remainingMsOrZero(deadline);
            if (wait <= 0) {
                run.abandon();
                pending.cancel(true);
                return timeout("MSC query had no time left to start");
            }
            return pending.get(wait, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // The caller stops waiting, but the worker still owns and closes
            // the session once its current call returns.
            run.abandon();
            pending.cancel(true);
            circuitBreaker.recordFailure();
            return timeout("MSC query exceeded its " + budget + "ms budget");
        } catch (InterruptedException e) {
            run.abandon();
            pending.cancel(true);
            Thread.currentThread().interrupt();
            return timeout("collector interrupted during the MSC query");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("browser query failed", cause);
        }
    }

    /**
     * Runs entirely on the browser thread so startup, page work and cleanup
     * never overlap with another query using the same Playwright instance.
     */
    private TrackingOutcome executeQuery(String containerNo, long budget, long deadline, RunToken run) {
        BrowserSession session = null;
        try {
            throttle(deadline);
            long openBudget = remainingMsOrZero(deadline);
            if (openBudget <= 0) {
                throw new DeadlineExceeded();
            }
            session = sessionFactory.open(openBudget);
            if (run.abandoned() || Thread.currentThread().isInterrupted()) {
                return timeout("MSC query was abandoned before it reached the page");
            }
            if (remainingMsOrZero(deadline) <= 0) {
                throw new DeadlineExceeded();
            }
            return queryWithSession(session.page(), containerNo, deadline, run);
        } catch (DeadlineExceeded e) {
            recordFailure(run);
            return timeout("MSC query exceeded its " + budget + "ms budget");
        } catch (TimeoutError e) {
            recordFailure(run);
            return timeout("MSC page timeout: " + firstLine(e.getMessage()));
        } catch (PlaywrightException e) {
            recordFailure(run);
            log.warn("Browser failure while querying {}: {}", containerNo, firstLine(e.getMessage()));
            return new TrackingOutcome.Failure(AgentErrorCode.SOURCE_UNAVAILABLE,
                    "browser error: " + firstLine(e.getMessage()));
        } finally {
            closeQuietly(session);
        }
    }

    private TrackingOutcome queryWithSession(Page page, String containerNo, long deadline, RunToken run) {
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
                recordFailure(run);
                return new TrackingOutcome.Failure(statusError, "MSC page returned HTTP " + status);
            }
        }

        // Classify the landing page before touching the form: a captcha or a
        // forced login must stop here, not burn the remaining budget.
        MscPageSnapshot landing = capture(page, deadline, true);
        Optional<AgentErrorCode> landingError = errorClassifier.classify(landing);
        if (landingError.isPresent()) {
            return signalFailure(landingError.get(), landing, run);
        }

        Locator input;
        try {
            page.waitForSelector(properties.getContainerInputSelector(),
                    new Page.WaitForSelectorOptions().setTimeout(formWaitMs(deadline)));
            input = page.locator(properties.getContainerInputSelector()).first();
        } catch (TimeoutError e) {
            // The form itself is missing: that is selector drift, unless the
            // whole budget is already gone.
            return formFailure(deadline, run,
                    "MSC tracking form was not found; the page structure likely changed");
        }

        // Record what the page already shows for this container. A block that
        // was there before the click has not been produced by this query, so
        // it may only be read once the page or the network proves a refresh.
        ResultIdentity beforeSubmit = visibleResultIdentity(page, containerNo, deadline);

        AtomicReference<SubmitResponse> correlated = new AtomicReference<>();
        AtomicReference<SubmitResponse> fallbackError = new AtomicReference<>();
        AtomicReference<SubmitResponse> fallbackAny = new AtomicReference<>();
        // Captured before the click so a 429/5xx from the tracking request is
        // still seen when the page renders no error text at all. A redirect
        // chain keeps the correlation: the final response of the tracking call
        // is the one that decides, not the 302 that started it.
        Consumer<Response> responseListener = response -> {
            SubmitResponse signal = submitSignal(response, containerNo);
            if (signal == null) {
                return;
            }
            if (signal.correlated()) {
                // Last response of the chain wins: 302 followed by 429 must end
                // as RATE_LIMITED.
                correlated.set(signal);
            } else {
                fallbackAny.set(signal);
                if (signal.status() >= 400) {
                    fallbackError.set(signal);
                }
            }
        };
        page.onResponse(responseListener);
        try {
            try {
                input.fill(containerNo, new Locator.FillOptions().setTimeout(remainingMs(deadline)));
                page.locator(properties.getSubmitSelector()).first()
                        .click(new Locator.ClickOptions().setTimeout(remainingMs(deadline)));
            } catch (TimeoutError e) {
                return formFailure(deadline, run, "MSC tracking form could not be filled or submitted");
            }

            WaitResult wait = waitForResult(page, containerNo, deadline, correlated, beforeSubmit);
            MscPageSnapshot snapshot = capture(page, deadline, false);

            Optional<AgentErrorCode> error = errorClassifier.classify(snapshot);
            if (error.isPresent()) {
                return signalFailure(error.get(), snapshot, run);
            }

            SubmitResponse response = effectiveResponse(correlated, fallbackError, fallbackAny);
            // A result block for the queried container wins over an unrelated
            // background failure. Only a correlated tracking error, or a
            // failure when no result rendered, is terminal here.
            if (response != null && response.status() >= 400
                    && (response.correlated() || wait != WaitResult.RESULT)) {
                AgentErrorCode code = statusError(response.status());
                if (code != null) {
                    if (response.status() == 429) {
                        deferAfterRateLimit(response.retryAfterSeconds());
                    }
                    recordFailure(run);
                    return new TrackingOutcome.Failure(code,
                            "MSC tracking request returned HTTP " + response.status());
                }
            }
            if (remainingMsOrZero(deadline) <= 0) {
                recordFailure(run);
                return timeout("MSC query exceeded its budget before parsing the result");
            }
            // A block that was already visible before submission was never
            // proven to belong to this query. Publishing it is exactly the
            // stale-result bug AC-29 forbids.
            if (wait != WaitResult.RESULT && snapshot.resultBlockText() != null) {
                recordFailure(run);
                return timeout("MSC result block was not refreshed for container " + containerNo);
            }
            // Re-check the captured block against the pre-submit identity: a
            // DOM that reverted between the wait and the capture must not be
            // published as this query's result.
            if (beforeSubmit != null && snapshot.resultBlockText() != null
                    && fingerprint(snapshot.resultBlockText()).equals(beforeSubmit.text())
                    && !(response != null && response.status() >= 200 && response.status() < 300)) {
                recordFailure(run);
                return timeout("MSC result block is still the pre-submit result for container "
                        + containerNo);
            }

            try {
                ParsedTracking parsed = parser.parse(snapshot, containerNo);
                recordSuccess(run);
                return new TrackingOutcome.Success(parsed, snapshot);
            } catch (MscParseException e) {
                recordFailure(run);
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

    private TrackingOutcome signalFailure(AgentErrorCode code, MscPageSnapshot snapshot, RunToken run) {
        if (code != AgentErrorCode.CONTAINER_NOT_FOUND) {
            recordFailure(run);
        }
        return new TrackingOutcome.Failure(code, describe(code, snapshot));
    }

    private void recordFailure(RunToken run) {
        if (!run.abandoned()) {
            circuitBreaker.recordFailure();
        }
    }

    private void recordSuccess(RunToken run) {
        if (!run.abandoned()) {
            circuitBreaker.recordSuccess();
        }
    }

    private TrackingOutcome timeout(String message) {
        return new TrackingOutcome.Failure(AgentErrorCode.TIMEOUT, message);
    }

    /**
     * A form timeout means selector drift only while budget remains; once the
     * deadline is gone it is a genuine timeout.
     */
    private TrackingOutcome formFailure(long deadline, RunToken run, String message) {
        recordFailure(run);
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
     * Polls for the page's terminal state: a fresh result block, a
     * captcha/login signal, or a 429/5xx from the tracking request. This is
     * what lets a late captcha be reported as such instead of waiting the full
     * budget for a result selector that will never appear.
     */
    private WaitResult waitForResult(Page page, String containerNo, long deadline,
                                     AtomicReference<SubmitResponse> correlated,
                                     ResultIdentity beforeSubmit) {
        long resultDeadline = Math.min(deadline,
                System.nanoTime() + Math.max(0, properties.getResultWaitMs()) * 1_000_000L);
        while (true) {
            String text = visibleResultText(page, resultDeadline);
            SubmitResponse response = correlated.get();
            if (response != null && response.status() >= 400) {
                return WaitResult.HTTP_ERROR;
            }
            // A visible block only counts when it names the queried container
            // and can be shown to belong to this query: either it appeared
            // after the click, its content changed, or the carrier answered the
            // tracking request with a final success.
            if (text != null && normalizeContainer(text).contains(normalizeContainer(containerNo))
                    && isFresh(beforeSubmit, text, response)) {
                return WaitResult.RESULT;
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

    private static boolean isFresh(ResultIdentity beforeSubmit, String text, SubmitResponse response) {
        if (beforeSubmit == null) {
            // Nothing was visible before the click, so this block is new.
            return true;
        }
        if (!fingerprint(text).equals(beforeSubmit.text())) {
            return true;
        }
        // Unchanged text is still fresh when the carrier answered the tracking
        // request for this container with a final 2xx after the click.
        return response != null && response.status() >= 200 && response.status() < 300;
    }

    private static SubmitResponse effectiveResponse(AtomicReference<SubmitResponse> correlated,
                                                    AtomicReference<SubmitResponse> fallbackError,
                                                    AtomicReference<SubmitResponse> fallbackAny) {
        SubmitResponse tracked = correlated.get();
        if (tracked != null) {
            return tracked;
        }
        SubmitResponse error = fallbackError.get();
        return error != null ? error : fallbackAny.get();
    }

    private static String normalizeContainer(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static String fingerprint(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    SubmitResponse submitSignal(Response response, String containerNo) {
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
            String token = normalizeContainer(containerNo);
            boolean direct = mentionsContainer(response.request(), token);
            // Follow the redirect chain: the request that carries the container
            // number answers with 302, and the final response (possibly a 429)
            // no longer mentions it. Without the chain the 302 would win and
            // the rate limit would be reported as a timeout.
            boolean redirected = followsTrackingRedirect(response.request(), token);
            return new SubmitResponse(response.status(), response.headerValue("retry-after"),
                    direct || redirected);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * A request is the tracking call when its URL or body carries the queried
     * container number. Background beacons and config calls fail this check, so
     * they can never stand in for the tracking response.
     */
    private static boolean mentionsContainer(Request request, String token) {
        if (token.isEmpty()) {
            return false;
        }
        try {
            String url = request.url();
            if (url != null && normalizeContainer(url).contains(token)) {
                return true;
            }
            String postData = request.postData();
            return postData != null && normalizeContainer(postData).contains(token);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean followsTrackingRedirect(Request request, String token) {
        if (token.isEmpty()) {
            return false;
        }
        try {
            Request previous = request.redirectedFrom();
            int hops = 0;
            while (previous != null && hops < MAX_REDIRECT_HOPS) {
                if (mentionsContainer(previous, token)) {
                    return true;
                }
                previous = previous.redirectedFrom();
                hops++;
            }
        } catch (RuntimeException ignored) {
            // A missing redirect link simply means this response is unrelated.
        }
        return false;
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
        String title = safe(page::title);
        String url = safe(page::url);
        String body = safe(() -> page.locator("body")
                .innerText(new Locator.InnerTextOptions().setTimeout(captureTimeout(deadline))));
        if (body != null && body.length() > properties.getMaxBodyChars()) {
            body = body.substring(0, properties.getMaxBodyChars());
        }

        boolean passwordField = visible(page, properties.getLoginFieldSelector());
        if (light) {
            return new MscPageSnapshot(title, url, body, null, List.of(), passwordField);
        }

        // Read the status and timeline only from the visible result block, and
        // only from elements that are actually visible. A hidden node inside
        // the same block can carry another container's "picked up" status; the
        // page text must never let that become this container's result.
        Locator block = visibleResultBlock(page);
        String blockText = null;
        boolean ambiguous = false;
        String statusText = null;
        List<String> eventLines = new ArrayList<>();
        if (block != null) {
            blockText = innerText(block, captureTimeout(deadline));
            if (blockText != null && blockText.length() > properties.getMaxBodyChars()) {
                blockText = blockText.substring(0, properties.getMaxBodyChars());
            }
        }
        if (blockText != null) {
            List<String> statuses = collapseRelated(
                    visibleTexts(block, properties.getStatusSelector(), deadline));
            Set<String> distinct = distinctTexts(statuses);
            if (distinct.size() > 1) {
                // Two visible, different status values: ownership cannot be
                // established, so the parser must refuse the block.
                ambiguous = true;
            } else if (distinct.size() == 1) {
                statusText = statuses.getFirst();
            }

            try {
                Locator rows = block.locator(properties.getEventRowSelector());
                int count = Math.min(rows.count(), 50);
                for (int i = 0; i < count; i++) {
                    Locator row = rows.nth(i);
                    if (!row.isVisible()) {
                        continue;
                    }
                    String line = innerText(row, Math.min(500, captureTimeout(deadline)));
                    if (line != null) {
                        eventLines.add(line);
                    }
                }
            } catch (PlaywrightException ignored) {
                // Optional: the timeline may be absent on a "no result" page.
            }
        }

        return new MscPageSnapshot(title, url, body, statusText, eventLines,
                passwordField, blockText, ambiguous);
    }

    private static List<String> visibleTexts(Locator scope, String selector, long deadline) {
        List<String> values = new ArrayList<>();
        try {
            Locator items = scope.locator(selector);
            int count = Math.min(items.count(), 20);
            int timeout = Math.min(500, captureTimeout(deadline));
            for (int i = 0; i < count; i++) {
                Locator item = items.nth(i);
                if (!item.isVisible()) {
                    continue;
                }
                String text = innerText(item, timeout);
                if (text != null && !text.isBlank()) {
                    values.add(text.trim());
                }
            }
        } catch (PlaywrightException ignored) {
            // Optional field: absence is reported by the parser, not here.
        }
        return values;
    }

    /**
     * Nested selectors often match both a label container and its value, so
     * their texts contain one another. Those describe one status; only
     * genuinely different, non-contained texts are treated as a conflict.
     */
    private static List<String> collapseRelated(List<String> values) {
        List<String> collapsed = new ArrayList<>();
        for (String raw : values) {
            String normalized = normalizeText(raw);
            boolean merged = false;
            for (int i = 0; i < collapsed.size(); i++) {
                String existing = normalizeText(collapsed.get(i));
                if (existing.contains(normalized) || normalized.contains(existing)) {
                    if (normalized.length() > existing.length()) {
                        collapsed.set(i, raw.trim());
                    }
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                collapsed.add(raw.trim());
            }
        }
        return collapsed;
    }

    private static String normalizeText(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static Set<String> distinctTexts(List<String> values) {
        Set<String> distinct = new LinkedHashSet<>();
        for (String value : values) {
            distinct.add(value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT));
        }
        return distinct;
    }

    private static String innerText(Locator locator, int timeoutMs) {
        try {
            return locator.innerText(new Locator.InnerTextOptions().setTimeout(timeoutMs));
        } catch (PlaywrightException e) {
            return null;
        }
    }

    private static int captureTimeout(long deadline) {
        return Math.min(2_000, remainingMs(deadline));
    }

    private String visibleResultText(Page page, long deadline) {
        Locator result = visibleResultBlock(page);
        if (result == null) {
            return null;
        }
        int timeout = (int) Math.min(500, Math.max(1, remainingMsOrZero(deadline)));
        return innerText(result, timeout);
    }

    private ResultIdentity visibleResultIdentity(Page page, String containerNo, long deadline) {
        String text = visibleResultText(page, deadline);
        if (text == null || !normalizeContainer(text).contains(normalizeContainer(containerNo))) {
            return null;
        }
        return new ResultIdentity(fingerprint(text));
    }

    private Locator visibleResultBlock(Page page) {
        try {
            Locator result = page.locator(properties.getResultSelector()).first();
            if (result.count() == 0 || !result.isVisible()) {
                return null;
            }
            return result;
        } catch (PlaywrightException e) {
            return null;
        }
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

    private static void closeQuietly(BrowserSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (RuntimeException ignored) {
            // A crashed browser can fail to close; nothing to retry.
        }
    }

    @PreDestroy
    void shutdown() {
        browserThread.shutdownNow();
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

    record SubmitResponse(int status, String retryAfterSeconds, boolean correlated) {
    }

    private record ResultIdentity(String text) {
    }

    /**
     * One query attempt. The caller marks it abandoned when its budget runs
     * out; the worker checks the flag so a late session is closed instead of
     * being used for a result nobody waits for.
     */
    private static final class RunToken {
        private final AtomicBoolean abandoned = new AtomicBoolean();

        void abandon() {
            abandoned.set(true);
        }

        boolean abandoned() {
            return abandoned.get();
        }
    }

    /** Internal signal that the per-query budget is spent. */
    private static final class DeadlineExceeded extends RuntimeException {
        private DeadlineExceeded() {
            super(null, null, false, false);
        }
    }
}
