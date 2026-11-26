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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import java.util.regex.Pattern;

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
    /**
     * Lower bound for the settle window. Config may lengthen it but a zero or
     * negative setting cannot skip the mandatory second read.
     */
    static final long MIN_SETTLE_MS = 500;
    private static final int MAX_REDIRECT_HOPS = 10;
    private static final int MAX_RESULT_BLOCKS = 10;
    /** Tolerance for the millisecond clock Playwright uses for request timing. */
    private static final long START_TIME_SKEW_MS = 200;

    /**
     * Marks the result content (status, summary cells, timeline rows) before
     * submission so a later read can prove the query rebuilt it. A page that
     * only rewrites a label, drops a row or reveals a sibling leaves the marks
     * in place and can never be adopted.
     */
    private static final String PROBE_ATTRIBUTE = "data-shiptrack-probe";

    private final BrowserSessionFactory sessionFactory;
    private final MscProperties properties;
    private final MscResultParser parser;
    private final MscErrorClassifier errorClassifier;
    private final QueryCircuitBreaker circuitBreaker;
    /** Path pattern that identifies the carrier's real tracking endpoint. */
    private final Pattern trackingPath;

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
        String pattern = properties.getTrackingUrlPattern();
        this.trackingPath = pattern == null || pattern.isBlank()
                ? Pattern.compile("$^")
                : Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
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
        MscPageSnapshot landing = capture(page, containerNo, deadline, true);
        Optional<AgentErrorCode> landingError = errorClassifier.classify(landing);
        if (landingError.isPresent()) {
            return signalFailure(landingError.get(), landing, run);
        }

        // The OneTrust consent layer sits above the tracking form and
        // intercepts the submit click until it is accepted.
        dismissConsent(page, deadline);

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

        // Record every block that already shows this container, including
        // hidden ones: a block that was there before the click was not
        // produced by this query. Each candidate is stamped with its own
        // marker so a later read can tell a re-render from a block that was
        // merely revealed.
        ResultBaseline beforeSubmit = recordResultBaseline(page, containerNo, deadline);

        long submitStartedAt = System.currentTimeMillis();
        AtomicReference<SubmitResponse> correlated = new AtomicReference<>();
        AtomicReference<SubmitResponse> fallbackError = new AtomicReference<>();
        AtomicReference<SubmitResponse> fallbackAny = new AtomicReference<>();
        // Captured before the click so a 429/5xx from the tracking request is
        // still seen when the page renders no error text at all. Only a call
        // that started with this submit, targets a tracking endpoint and
        // carries the container may be correlated; an analytics beacon that
        // happens to embed the number or a background config call cannot.
        Consumer<Response> responseListener = response -> {
            SubmitResponse signal = submitSignal(response, containerNo, submitStartedAt);
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
                dismissConsent(page, deadline);
                submitLocator(page)
                        .click(new Locator.ClickOptions().setTimeout(remainingMs(deadline)));
            } catch (TimeoutError e) {
                return formFailure(deadline, run, "MSC tracking form could not be filled or submitted");
            }

            WaitOutcome wait = waitForResult(page, containerNo, deadline, correlated, beforeSubmit);
            // Parse exactly the read that proved a fresh render. Re-reading the
            // block here would let the page change between the ownership check
            // and the parse, which is the staging window this fix closes.
            MscPageSnapshot snapshot =
                    capture(page, containerNo, deadline, false, wait.observation());

            Optional<AgentErrorCode> error = errorClassifier.classify(snapshot);
            if (error.isPresent()) {
                return signalFailure(error.get(), snapshot, run);
            }

            SubmitResponse response = effectiveResponse(correlated, fallbackError, fallbackAny);
            // A result block for the queried container wins over an unrelated
            // background failure. Only a correlated tracking error, or a
            // failure when no result rendered, is terminal here.
            if (response != null && response.status() >= 400
                    && (response.correlated() || wait.result() != WaitResult.RESULT)) {
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
            // stale-result bug AC-29 forbids. When no fresh render was
            // observed, the snapshot above holds the stale block (if any), so
            // this is a classified timeout, never a success.
            if (wait.result() != WaitResult.RESULT && snapshot.resultBlockText() != null) {
                recordFailure(run);
                return timeout("MSC result block was not refreshed for container " + containerNo);
            }

            try {
                ParsedTracking parsed = parser.parse(snapshot, containerNo);
                recordSuccess(run);
                return new TrackingOutcome.Success(parsed, snapshot);
            } catch (MscParseException e) {
                recordFailure(run);
                return new TrackingOutcome.Failure(
                        resolveUnparsable(snapshot, containerNo, wait.result() == WaitResult.RESULT),
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

    /**
     * Resolves the form's submit control. The precise selector comes first;
     * the broad legacy selector is only a fallback for older layouts, and a
     * match that is really the header myMSC login/sign-in link is skipped so
     * a query never turns into a redirect to the identity provider.
     */
    private Locator submitLocator(Page page) {
        Locator precise = page.locator(properties.getSubmitSelector());
        if (precise.count() > 0) {
            return precise.first();
        }
        Locator legacy = page.locator(properties.getLegacySubmitSelector());
        int candidates = legacy.count();
        for (int i = 0; i < candidates; i++) {
            Locator candidate = legacy.nth(i);
            if (!looksLikeLoginControl(candidate)) {
                return candidate;
            }
        }
        // Nothing usable: clicking this surfaces the usual timeout, which the
        // caller already reports as a form failure.
        return precise.first();
    }

    private static boolean looksLikeLoginControl(Locator candidate) {
        try {
            String text = candidate.innerText();
            String className = candidate.getAttribute("class");
            String href = candidate.getAttribute("href");
            String haystack = ((text == null ? "" : text) + " "
                    + (className == null ? "" : className) + " "
                    + (href == null ? "" : href)).toLowerCase(Locale.ROOT);
            return haystack.contains("mymsc")
                    || haystack.contains("login")
                    || haystack.contains("log in")
                    || haystack.contains("sign in");
        } catch (PlaywrightException e) {
            return false;
        }
    }

    /**
     * Accepts the cookie consent if one is shown, then drops any overlay node
     * that survived. The context is thrown away after the query, so nothing
     * is remembered beyond it; without this the consent dark filter swallows
     * every click on the form.
     */
    private void dismissConsent(Page page, long deadline) {
        List<String> selectors = properties.getConsentSelectors();
        if (selectors != null) {
            for (String selector : selectors) {
                if (selector == null || selector.isBlank()) {
                    continue;
                }
                Locator control = page.locator(selector);
                try {
                    if (control.count() == 0 || !control.first().isVisible()) {
                        continue;
                    }
                    long timeout = Math.min(5_000, Math.max(1, remainingMsOrZero(deadline)));
                    control.first().click(new Locator.ClickOptions().setTimeout(timeout));
                    log.info("Dismissed the MSC cookie consent overlay");
                    break;
                } catch (PlaywrightException e) {
                    log.debug("Cookie consent control {} was not clickable: {}",
                            selector, firstLine(e.getMessage()));
                }
            }
        }
        try {
            page.evaluate("() => { document.querySelectorAll("
                    + "'#onetrust-consent-sdk, .onetrust-pc-dark-filter')"
                    + ".forEach(node => node.remove()); }");
        } catch (PlaywrightException e) {
            log.debug("Could not clear a leftover consent overlay: {}", firstLine(e.getMessage()));
        }
    }

    static AgentErrorCode statusError(int status) {
        if (status == 401) {
            return AgentErrorCode.LOGIN_REQUIRED;
        }
        if (status == 403) {
            return AgentErrorCode.SOURCE_UNAVAILABLE;
        }
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
    private WaitOutcome waitForResult(Page page, String containerNo, long deadline,
                                      AtomicReference<SubmitResponse> correlated,
                                      ResultBaseline beforeSubmit) {
        long resultDeadline = Math.min(deadline,
                System.nanoTime() + Math.max(0, properties.getResultWaitMs()) * 1_000_000L);
        while (true) {
            ResultObservation render = observeResult(page, containerNo, resultDeadline, beforeSubmit);
            SubmitResponse response = correlated.get();
            if (response != null && response.status() >= 400) {
                return new WaitOutcome(WaitResult.HTTP_ERROR, null);
            }
            // All three must hold: the tracking call succeeded, the visible
            // block carries no pre-submit stamp (this query rebuilt the
            // result), and its facts stay identical over the settle window.
            // A block that still carries a stamp is never adopted, even after
            // it drops a row, rewrites a label or replaces another pre-existing
            // block, so a staged page cannot publish its old status.
            if (render != null && !render.ambiguous()
                    && renderFresh(beforeSubmit, render)
                    && trackingSucceeded(response)) {
                ResultObservation settled = confirmSettled(
                        page, containerNo, resultDeadline, beforeSubmit, render);
                if (settled != null) {
                    return new WaitOutcome(WaitResult.RESULT, settled);
                }
            }
            if (errorClassifier.classify(capture(page, containerNo, deadline, true)).isPresent()) {
                return new WaitOutcome(WaitResult.SIGNAL, render);
            }
            long remaining = remainingMsOrZero(resultDeadline);
            if (remaining <= 0) {
                return new WaitOutcome(WaitResult.PENDING, render);
            }
            sleep(Math.min(POLL_INTERVAL_MS, remaining));
        }
    }

    /**
     * The single freshness invariant: the visible block must carry none of the
     * stamps this attempt put on the pre-submit blocks. A surviving stamp
     * proves the block is the pre-submit one, no matter how its text changed.
     * When the landing page showed no block for this container there is
     * nothing to compare with, so the block is new.
     */
    private static boolean renderFresh(ResultBaseline before, ResultObservation now) {
        return now.marker() == null;
    }

    /** Only a correlated tracking call that answered 2xx may yield a result. */
    private static boolean trackingSucceeded(SubmitResponse response) {
        return response != null && response.correlated()
                && response.status() >= 200 && response.status() < 300;
    }

    /**
     * Re-reads the freshly built block after the settle window. Adoption
     * requires that the second read is still marker-free and shows the same
     * facts; anything else is a render still in progress and is not published.
     */
    private ResultObservation confirmSettled(Page page, String containerNo, long deadline,
                                             ResultBaseline before,
                                             ResultObservation first) {
        // The settle window can be widened by config but never disabled: a
        // zero or negative value still forces a second read, so a render that
        // is still staging its content can never be adopted from one snapshot.
        long settleMs = Math.max(MIN_SETTLE_MS, properties.getResultSettleMs());
        if (remainingMsOrZero(deadline) < settleMs) {
            // Not enough budget left to prove stability.
            return null;
        }
        sleep(settleMs);
        if (remainingMsOrZero(deadline) <= 0) {
            return null;
        }
        ResultObservation second = observeResult(page, containerNo, deadline, before);
        if (second == null || second.ambiguous() || second.marker() != null) {
            return null;
        }
        return sameFacts(first.facts(), second.facts()) ? second : null;
    }

    private static boolean sameFacts(ResultFacts first, ResultFacts second) {
        return normalizeText(first.status()).equals(normalizeText(second.status()))
                && normalizedMap(first.summary()).equals(normalizedMap(second.summary()))
                && normalizedRows(first.eventRows()).equals(normalizedRows(second.eventRows()));
    }

    private static Map<String, String> normalizedMap(Map<String, String> values) {
        Map<String, String> normalized = new LinkedHashMap<>();
        values.forEach((key, value) -> normalized.put(normalizeText(key), normalizeText(value)));
        return normalized;
    }

    private static List<List<String>> normalizedRows(List<List<String>> rows) {
        List<List<String>> normalized = new ArrayList<>(rows.size());
        for (List<String> row : rows) {
            normalized.add(normalizedLines(row));
        }
        return normalized;
    }

    private static List<String> normalizedLines(List<String> lines) {
        List<String> normalized = new ArrayList<>(lines.size());
        for (String line : lines) {
            normalized.add(normalizeText(line));
        }
        return normalized;
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

    SubmitResponse submitSignal(Response response, String containerNo, long submitStartedAt) {
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
            boolean tracking = trackingChain(response.request(), normalizeContainer(containerNo),
                    submitStartedAt);
            return new SubmitResponse(response.status(), response.headerValue("retry-after"),
                    tracking);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Walks the redirect chain back to the request that started it. Only a call
     * that began with this submit, targets a tracking endpoint and carries the
     * container number is the tracking call; the final 429 of a 302 chain no
     * longer names the box, but its originating request does.
     */
    private boolean trackingChain(Request request, String token, long submitStartedAt) {
        if (token.isEmpty()) {
            return false;
        }
        try {
            Request current = request;
            int hops = 0;
            while (current != null && hops <= MAX_REDIRECT_HOPS) {
                if (isTrackingCall(current, token) && startedAfter(current, submitStartedAt)) {
                    return true;
                }
                current = current.redirectedFrom();
                hops++;
            }
        } catch (RuntimeException ignored) {
            // A missing redirect link simply means this response is unrelated.
        }
        return false;
    }

    /**
     * A tracking call carries the queried container and targets the carrier's
     * tracking path. A beacon that embeds the number on an analytics path, or
     * a config call that does not name it, fails this test.
     */
    private boolean isTrackingCall(Request request, String token) {
        if (!mentionsContainer(request, token)) {
            return false;
        }
        return trackingPath.matcher(pathOf(request.url())).find();
    }

    /** The request must belong to this submission, not a call already in flight. */
    private static boolean startedAfter(Request request, long submitStartedAt) {
        try {
            double start = request.timing().startTime;
            // Playwright reports Unix epoch milliseconds. If it has no timing,
            // the endpoint and container checks above still identify the call.
            return start <= 0 || start >= submitStartedAt - START_TIME_SKEW_MS;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String pathOf(String url) {
        try {
            String path = URI.create(url).getPath();
            return path == null ? "" : path;
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * A request carries the queried container number when its URL or its POST
     * body names it.
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
    private MscPageSnapshot capture(Page page, String containerNo, long deadline, boolean light) {
        return capture(page, containerNo, deadline, light, null);
    }

    /**
     * Captures the page. When {@code observation} is the read that proved a
     * fresh render, its facts are used verbatim instead of re-reading the
     * block: re-reading would open a window in which the page could change
     * again after freshness was established.
     */
    private MscPageSnapshot capture(Page page, String containerNo, long deadline, boolean light,
                                    ResultObservation observation) {
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

        if (observation != null) {
            String provenText = observation.blockText();
            if (provenText != null && provenText.length() > properties.getMaxBodyChars()) {
                provenText = provenText.substring(0, properties.getMaxBodyChars());
            }
            ResultFacts proven = observation.facts();
            return new MscPageSnapshot(title, url, body, proven.status(), proven.events(),
                    passwordField, provenText, observation.ambiguous(),
                    proven.summary(), proven.eventRows());
        }

        // Read the status and timeline only from the visible result block that
        // names the queried container, and only from visible elements. A hidden
        // node inside another block can carry a different box's "picked up";
        // the page text must never let that become this container's result.
        List<Locator> blocks = resultBlocksFor(page, containerNo, false, deadline);
        Locator block = blocks.isEmpty() ? null : blocks.getFirst();
        boolean ambiguous = blocks.size() > 1;
        String blockText = null;
        String statusText = null;
        List<String> eventLines = List.of();
        Map<String, String> summaryFacts = Map.of();
        List<List<String>> eventRows = List.of();
        if (block != null) {
            blockText = innerText(block, captureTimeout(deadline));
            if (blockText != null && blockText.length() > properties.getMaxBodyChars()) {
                blockText = blockText.substring(0, properties.getMaxBodyChars());
            }
            ResultFacts facts = factsOf(block, deadline, true);
            statusText = facts.status();
            eventLines = facts.events();
            summaryFacts = facts.summary();
            eventRows = facts.eventRows();
            ambiguous = ambiguous || facts.ambiguous();
        }
        return new MscPageSnapshot(title, url, body, statusText, eventLines,
                passwordField, blockText, ambiguous, summaryFacts, eventRows);
    }

    /**
     * Reads status and timeline from one block. A live result uses only visible
     * nodes; the pre-submit baseline also sees hidden ones so a stale block
     * cannot hide until the click reveals it.
     */
    private ResultFacts factsOf(Locator block, long deadline, boolean visibleOnly) {
        int timeout = Math.min(500, boundedTimeout(deadline));
        List<String> statuses = collapseRelated(
                textsOf(nodes(block, properties.getStatusSelector(), visibleOnly), timeout, visibleOnly));
        Set<String> distinct = distinctTexts(statuses);
        String status = distinct.size() == 1 ? statuses.getFirst() : null;
        boolean ambiguous = distinct.size() > 1;

        Map<String, String> summary = new LinkedHashMap<>();
        for (Locator cell : nodes(block, properties.getSummarySelector(), visibleOnly)) {
            String heading = firstText(cell, properties.getSummaryHeadingSelector(), timeout, visibleOnly);
            String value = firstText(cell, properties.getSummaryValueSelector(), timeout, visibleOnly);
            if (heading != null && value != null) {
                String key = heading.trim();
                String existing = summary.putIfAbsent(key, value.trim());
                if (existing != null && !normalizeText(existing).equals(normalizeText(value))) {
                    // Two different values under one heading: the block does
                    // not describe one shipment.
                    ambiguous = true;
                }
            }
        }

        List<String> events = new ArrayList<>();
        List<List<String>> eventRows = new ArrayList<>();
        for (Locator row : nodes(block, properties.getEventRowSelector(), visibleOnly)) {
            String line = visibleOnly ? innerText(row, timeout) : textContent(row, timeout);
            if (line == null || line.isBlank()) {
                continue;
            }
            List<String> cells = new ArrayList<>(cellsOf(row, timeout, visibleOnly));
            if (cells.isEmpty()) {
                for (String cell : line.split("\\R")) {
                    String trimmed = cell.trim();
                    if (!trimmed.isEmpty()) {
                        cells.add(trimmed);
                    }
                }
            }
            if (cells.isEmpty()) {
                continue;
            }
            eventRows.add(cells);
            events.add(String.join(" | ", cells));
        }
        return new ResultFacts(status, events, summary, eventRows, ambiguous);
    }

    /**
     * The value cells of a structured event row (the live card places one
     * {@code data-value} span per column on a single visual line). At least two
     * cells are required: a lone match is not a table and is left to the plain
     * text fallback.
     */
    private List<String> cellsOf(Locator row, int timeoutMs, boolean visibleOnly) {
        List<String> cells = new ArrayList<>();
        try {
            Locator values = row.locator(properties.getSummaryValueSelector());
            int count = Math.min(values.count(), 20);
            for (int i = 0; i < count; i++) {
                Locator value = values.nth(i);
                if (visibleOnly && !value.isVisible()) {
                    continue;
                }
                String text = visibleOnly ? innerText(value, timeoutMs) : textContent(value, timeoutMs);
                if (text != null && !text.isBlank()) {
                    cells.add(text.trim());
                }
            }
        } catch (PlaywrightException e) {
            cells.clear();
        }
        return cells.size() >= 2 ? cells : List.of();
    }

    /** Text of the first matching child, or null when the selector finds none. */
    private static String firstText(Locator scope, String selector, int timeoutMs, boolean visibleOnly) {
        try {
            Locator items = scope.locator(selector);
            if (items.count() == 0) {
                return null;
            }
            Locator first = items.first();
            if (visibleOnly && !first.isVisible()) {
                return null;
            }
            String text = visibleOnly ? innerText(first, timeoutMs) : textContent(first, timeoutMs);
            return text == null || text.isBlank() ? null : text.trim();
        } catch (PlaywrightException e) {
            return null;
        }
    }

    private static List<Locator> nodes(Locator scope, String selector, boolean visibleOnly) {
        List<Locator> nodes = new ArrayList<>();
        try {
            Locator items = scope.locator(selector);
            int count = Math.min(items.count(), 50);
            for (int i = 0; i < count; i++) {
                Locator item = items.nth(i);
                if (visibleOnly && !item.isVisible()) {
                    continue;
                }
                nodes.add(item);
            }
        } catch (PlaywrightException ignored) {
            // Optional selector: absence is reported by the parser.
        }
        return nodes;
    }

    private static List<String> textsOf(List<Locator> nodes, int timeout, boolean visibleOnly) {
        List<String> values = new ArrayList<>();
        for (Locator node : nodes) {
            String text = visibleOnly ? innerText(node, timeout) : textContent(node, timeout);
            if (text != null && !text.isBlank()) {
                values.add(text.trim());
            }
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

    /**
     * Same cap as {@link #captureTimeout(long)} but never throws: the poll loop
     * reads the page at the very edge of its window and must still report a
     * classified outcome instead of aborting the wait.
     */
    private static int boundedTimeout(long deadline) {
        long remaining = remainingMsOrZero(deadline);
        return remaining <= 0 ? 1 : (int) Math.min(2_000, remaining);
    }

    /**
     * All result blocks that name the queried container. A live result requires
     * visibility; the baseline also inspects hidden blocks, because a block
     * that is merely revealed after the click is not this query's result.
     */
    private List<Locator> resultBlocksFor(Page page, String containerNo, boolean includeHidden,
                                          long deadline) {
        List<Locator> blocks = new ArrayList<>();
        String token = normalizeContainer(containerNo);
        if (token.isEmpty()) {
            return blocks;
        }
        int timeout = Math.min(500, boundedTimeout(deadline));
        try {
            Locator all = page.locator(properties.getResultSelector());
            int count = Math.min(all.count(), MAX_RESULT_BLOCKS);
            for (int i = 0; i < count; i++) {
                Locator block = all.nth(i);
                if (!includeHidden && !block.isVisible()) {
                    continue;
                }
                String text = includeHidden ? textContent(block, timeout) : innerText(block, timeout);
                if (text != null && normalizeContainer(text).contains(token)) {
                    blocks.add(block);
                }
            }
        } catch (PlaywrightException ignored) {
            // No result container rendered yet.
        }
        return blocks;
    }

    /**
     * Reads the visible result block for this container in one pass: identity,
     * container text, status and timeline. {@code before} supplies the
     * pre-submit stamps, so the observation can report which candidate block
     * (if any) it is looking at.
     */
    private ResultObservation observeResult(Page page, String containerNo, long deadline,
                                            ResultBaseline before) {
        List<Locator> blocks = resultBlocksFor(page, containerNo, false, deadline);
        if (blocks.isEmpty()) {
            return null;
        }
        Locator block = blocks.getFirst();
        String blockText = innerText(block, captureTimeout(deadline));
        ResultFacts facts = factsOf(block, deadline, true);
        boolean ambiguous = facts.ambiguous() || blocks.size() > 1;
        String marker = before == null ? null : markerOn(block, before);
        return new ResultObservation(blockText, facts, ambiguous, marker);
    }

    /**
     * Records every block that already exists for this container before the
     * click and stamps its content with a unique marker. A later read proves
     * freshness by that stamp being gone: the query rebuilt the content. The
     * block root is stamped only when it has no stampable content, so a page
     * that replaces the children of a stable container still counts as a
     * rebuild. Marking hidden candidates means a second pre-existing block
     * that is merely revealed cannot pass as new.
     */
    private ResultBaseline recordResultBaseline(Page page, String containerNo, long deadline) {
        List<Locator> blocks = resultBlocksFor(page, containerNo, true, deadline);
        if (blocks.isEmpty()) {
            return null;
        }
        List<BlockBaseline> baselines = new ArrayList<>(blocks.size());
        for (Locator block : blocks) {
            String marker = "m" + UUID.randomUUID().toString().replace("-", "");
            int marked = markContent(block, marker);
            if (marked == 0 && markNode(block, marker)) {
                // No status/summary/event node could be stamped; fall back to
                // the root. Such a block only counts as fresh when the whole
                // root is replaced, which is the conservative reading.
                marked = 1;
            }
            if (marked == 0) {
                // Could not stamp this candidate at all.
                continue;
            }
            baselines.add(new BlockBaseline(marker));
        }
        return baselines.isEmpty() ? null : new ResultBaseline(List.copyOf(baselines));
    }

    /** Which pre-submit stamp, if any, the visible block still carries. */
    private static String markerOn(Locator block, ResultBaseline baseline) {
        for (BlockBaseline candidate : baseline.blocks()) {
            if (countMarked(block, candidate.marker()) > 0) {
                return candidate.marker();
            }
        }
        return null;
    }

    /** Stamps the block's status/summary/timeline nodes; returns how many took it. */
    private int markContent(Locator block, String marker) {
        int marked = 0;
        for (Locator node : nodes(block, properties.getStatusSelector(), false)) {
            if (markNode(node, marker)) {
                marked++;
            }
        }
        for (Locator node : nodes(block, properties.getSummarySelector(), false)) {
            if (markNode(node, marker)) {
                marked++;
            }
        }
        for (Locator node : nodes(block, properties.getEventRowSelector(), false)) {
            if (markNode(node, marker)) {
                marked++;
            }
        }
        return marked;
    }

    private static boolean markNode(Locator node, String marker) {
        try {
            node.evaluate("(el, args) => { el.setAttribute(args[0], args[1]); }",
                    List.of(PROBE_ATTRIBUTE, marker));
            return true;
        } catch (PlaywrightException e) {
            return false;
        }
    }

    private static int countMarked(Locator block, String marker) {
        try {
            int count = marker.equals(block.getAttribute(PROBE_ATTRIBUTE)) ? 1 : 0;
            return count + block.locator("[" + PROBE_ATTRIBUTE + "='" + marker + "']").count();
        } catch (PlaywrightException e) {
            return 0;
        }
    }

    private static String textContent(Locator locator, int timeoutMs) {
        try {
            return locator.textContent(new Locator.TextContentOptions().setTimeout(timeoutMs));
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

    /** All pre-submit candidate blocks for this container, each with its stamp. */
    private record ResultBaseline(List<BlockBaseline> blocks) {
    }

    /** One pre-submit block and the stamp its content carries. */
    private record BlockBaseline(String marker) {
    }

    /**
     * A live read of the result block. {@code blockText} is the exact text the
     * parser will read, so ownership and parsing share one DOM read;
     * {@code marker} is the pre-submit stamp the block still carries, or null
     * when the page rebuilt it.
     */
    private record ResultObservation(String blockText, ResultFacts facts,
                                     boolean ambiguous, String marker) {
    }

    /** The terminal state of the wait plus the read that satisfied it. */
    private record WaitOutcome(WaitResult result, ResultObservation observation) {
    }

    private record ResultFacts(String status, List<String> events, Map<String, String> summary,
                               List<List<String>> eventRows, boolean ambiguous) {
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
