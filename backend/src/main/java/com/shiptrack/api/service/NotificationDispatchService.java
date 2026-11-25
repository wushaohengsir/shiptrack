package com.shiptrack.api.service;

import com.shiptrack.api.config.FeishuProperties;
import com.shiptrack.api.repo.NotificationRecord;
import com.shiptrack.api.repo.NotificationRepository;
import com.shiptrack.api.support.JsonStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * §7.4/§8.6 Feishu dispatcher: a polling consumer of the {@code notifications}
 * table. Claiming is an atomic {@code pending → sending} flip, write-backs are
 * conditional on the attempt's {@code sending_at}, and the HTTP call always
 * happens outside the database transaction that created the notification.
 *
 * <p>The dispatcher is single-threaded here (one instance, one scheduler): the
 * rate limiter is per process. Several instances can run safely because the
 * claim and write-back are conditional; only the per-process rate budget is
 * shared per instance.</p>
 */
@Service
@Profile("!migrate")
public class NotificationDispatchService {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatchService.class);
    private static final long MISSING_CONFIG_WARN_INTERVAL_MS = 600_000L;

    private final FeishuProperties properties;
    private final FeishuClient feishuClient;
    private final NotificationRepository notifications;
    private final JsonStore jsonStore;
    private final AppConfigService config;
    private final FeishuRateLimiter rateLimiter = new FeishuRateLimiter();
    private final AtomicLong lastMissingConfigWarn = new AtomicLong(0);

    public NotificationDispatchService(FeishuProperties properties, FeishuClient feishuClient,
                                       NotificationRepository notifications, JsonStore jsonStore,
                                       AppConfigService config) {
        this.properties = properties;
        this.feishuClient = feishuClient;
        this.notifications = notifications;
        this.jsonStore = jsonStore;
        this.config = config;
    }

    @Scheduled(fixedDelayString = "${app.feishu.dispatch-interval-ms:1000}",
            initialDelayString = "${app.feishu.dispatch-initial-delay-ms:1000}")
    public void scheduledDispatch() {
        if (!properties.isDispatchEnabled()) {
            return;
        }
        try {
            dispatchOnce();
        } catch (RuntimeException error) {
            log.error("notification dispatch tick failed", error);
        }
    }

    /**
     * One dispatch pass: recover hung attempts, requeue backed-off failures,
     * then claim and send up to {@code max-per-tick} notifications.
     *
     * @return number of notifications marked {@code sent}
     */
    public int dispatchOnce() {
        if (!properties.isDispatchEnabled()) {
            return 0;
        }
        if (!feishuClient.isConfigured()) {
            warnMissingConfig();
            return 0;
        }
        recoverStuck();
        requeueFailed();

        int sent = 0;
        for (int i = 0; i < Math.max(properties.getMaxPerTick(), 1); i++) {
            if (!rateLimiter.tryAcquire(properties.getRatePerSecond(), properties.getRatePerMinute())) {
                break;
            }
            Optional<Long> next = notifications.nextPendingId();
            if (next.isEmpty()) {
                break;
            }
            Optional<NotificationRepository.Claimed> claimed = notifications.claim(next.get());
            if (claimed.isEmpty()) {
                continue;
            }
            if (deliver(claimed.get())) {
                sent++;
            }
        }
        return sent;
    }

    private boolean deliver(NotificationRepository.Claimed claimed) {
        NotificationRecord notification = claimed.notification();
        Map<String, Object> payload = jsonStore.readMap(notification.payloadJson());
        List<String> candidates = FeishuMessageBuilder.candidates(
                payload, notification.notificationKey(), zone());
        FeishuClient.FeishuSendResult result;
        try {
            result = feishuClient.sendFirst(candidates);
        } catch (RuntimeException error) {
            result = FeishuClient.FeishuSendResult.failed(null,
                    "发送异常：" + error.getClass().getSimpleName());
        }
        if (result.skipped()) {
            notifications.releaseToPending(notification.id(), claimed.attemptToken());
            warnMissingConfig();
            return false;
        }
        if (result.success()) {
            int updated = notifications.markSent(notification.id(), claimed.attemptToken(),
                    result.responseCode(), result.messageId());
            if (updated == 0) {
                log.warn("discarding late Feishu response for notification {}", notification.id());
                return false;
            }
            log.info("notification {} sent (code {})", notification.id(), result.responseCode());
            return true;
        }
        int updated = notifications.markFailed(notification.id(), claimed.attemptToken(),
                result.error(), result.responseCode(),
                retryBackoffMicros(notification.retryCount()));
        if (updated == 0) {
            log.warn("discarding late Feishu failure for notification {}", notification.id());
        } else {
            log.warn("notification {} failed: {}", notification.id(), result.error());
        }
        return false;
    }

    private void recoverStuck() {
        long timeoutMicros = Duration.ofMillis(properties.getSendingTimeoutMs()).toNanos() / 1_000L;
        for (NotificationRepository.Claimed stuck
                : notifications.findStuckSending(timeoutMicros)) {
            NotificationRecord row = stuck.notification();
            int updated = notifications.markFailed(row.id(), stuck.attemptToken(),
                    "发送超时未回写", row.feishuResponseCode(),
                    retryBackoffMicros(row.retryCount()));
            if (updated > 0) {
                log.warn("notification {} recovered from a hung send", row.id());
            }
        }
    }

    private void requeueFailed() {
        for (NotificationRecord failed : notifications.findEligibleFailed()) {
            notifications.requeueFailed(failed.id());
        }
    }

    /** §7.4 backoff: first retry 1 min, second 5 min, then manual only. */
    private Long retryBackoffMicros(int retryCount) {
        if (retryCount <= 0) {
            return Duration.ofMillis(properties.getFirstRetryBackoffMs()).toNanos() / 1_000L;
        }
        if (retryCount == 1) {
            return Duration.ofMillis(properties.getSecondRetryBackoffMs()).toNanos() / 1_000L;
        }
        return null;
    }

    private ZoneId zone() {
        try {
            return ZoneId.of(config.values().timezone());
        } catch (RuntimeException error) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    private void warnMissingConfig() {
        long now = System.currentTimeMillis();
        long previous = lastMissingConfigWarn.get();
        if (now - previous >= MISSING_CONFIG_WARN_INTERVAL_MS
                && lastMissingConfigWarn.compareAndSet(previous, now)) {
            log.warn("FEISHU_WEBHOOK_URL is not configured; notifications stay pending");
        }
    }
}
