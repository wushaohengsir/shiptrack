package com.shiptrack.api.service;

import com.shiptrack.api.config.FeishuProperties;
import com.shiptrack.api.repo.NotificationRecord;
import com.shiptrack.api.repo.NotificationRepository;
import com.shiptrack.api.support.JsonStore;
import com.shiptrack.contract.enums.NotificationStatus;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * §7.4/§7.5 dispatch branches that need a controllable sender: missing config,
 * disabled dispatch, empty ticks and rate limiting.
 */
class NotificationDispatchServiceTest {

    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final FeishuClient feishuClient = mock(FeishuClient.class);
    private final AppConfigService config = mock(AppConfigService.class);
    private final JsonStore jsonStore = new JsonStore();

    @Test
    void missingConfigFailsClaimedRowsAndStillRecoversStuckSends() {
        FeishuProperties properties = properties("");
        when(feishuClient.isConfigured()).thenReturn(false);
        NotificationRepository.Claimed stuck = claimed(99, "stuck-token", 0);
        when(notifications.findStuckSending(anyLong())).thenReturn(List.of(stuck));
        when(notifications.nextPendingId())
                .thenReturn(Optional.of(1L)).thenReturn(Optional.empty());
        when(notifications.claim(1L)).thenReturn(Optional.of(claimed(1, "t1", 0)));
        when(notifications.markFailed(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(1);

        NotificationDispatchService dispatcher = new NotificationDispatchService(
                properties, feishuClient, notifications, jsonStore, config);

        assertEquals(0, dispatcher.dispatchOnce());

        verify(notifications).markFailed(eq(99L), eq("stuck-token"), eq("发送超时未回写"),
                isNull(), notNull());
        verify(notifications).markFailed(eq(1L), eq("t1"),
                eq(NotificationDispatchService.MISSING_CONFIG_ERROR), isNull(), isNull());
        verify(notifications, never()).findEligibleFailed();
        verify(feishuClient, never()).sendFirst(any());
    }

    @Test
    void explicitlyDisabledDispatchLeavesTheQueuePending() {
        FeishuProperties properties = properties("http://127.0.0.1:1/hook");
        properties.setDispatchEnabled(false);

        NotificationDispatchService dispatcher = new NotificationDispatchService(
                properties, feishuClient, notifications, jsonStore, config);

        assertEquals(0, dispatcher.dispatchOnce());
        verifyNoInteractions(notifications, feishuClient);
    }

    @Test
    void emptyTickDoesNotClaimOrCallFeishu() {
        FeishuProperties properties = properties("http://127.0.0.1:1/hook");
        when(feishuClient.isConfigured()).thenReturn(true);
        when(notifications.nextPendingId()).thenReturn(Optional.empty());

        NotificationDispatchService dispatcher = new NotificationDispatchService(
                properties, feishuClient, notifications, jsonStore, config);

        assertEquals(0, dispatcher.dispatchOnce());

        verify(notifications, never()).claim(anyLong());
        verify(notifications, never()).markFailed(anyLong(), anyString(), anyString(), any(), any());
        verify(feishuClient, never()).sendFirst(any());
    }

    @Test
    void rateLimitedAttemptIsFailedWithBackoffAndEndsTheRound() {
        FeishuProperties properties = properties("http://127.0.0.1:1/hook");
        properties.setRatePerSecond(0);
        properties.setRatePerMinute(100);
        when(feishuClient.isConfigured()).thenReturn(true);
        when(notifications.nextPendingId())
                .thenReturn(Optional.of(1L)).thenReturn(Optional.empty());
        when(notifications.claim(1L)).thenReturn(Optional.of(claimed(1, "t1", 0)));
        when(notifications.markFailed(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(1);

        NotificationDispatchService dispatcher = new NotificationDispatchService(
                properties, feishuClient, notifications, jsonStore, config);

        assertEquals(0, dispatcher.dispatchOnce());

        verify(notifications).markFailed(eq(1L), eq("t1"),
                eq(NotificationDispatchService.RATE_LIMITED_ERROR), isNull(), notNull());
        verify(feishuClient, never()).sendFirst(any());
    }

    @Test
    void skippedSendBecomesFailedInsteadOfPending() {
        FeishuProperties properties = properties("http://127.0.0.1:1/hook");
        when(feishuClient.isConfigured()).thenReturn(true);
        when(notifications.nextPendingId())
                .thenReturn(Optional.of(1L)).thenReturn(Optional.empty());
        when(notifications.claim(1L)).thenReturn(Optional.of(claimed(1, "t1", 0)));
        when(feishuClient.sendFirst(any()))
                .thenReturn(FeishuClient.FeishuSendResult.notConfigured());
        when(notifications.markFailed(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(1);

        NotificationDispatchService dispatcher = new NotificationDispatchService(
                properties, feishuClient, notifications, jsonStore, config);

        assertEquals(0, dispatcher.dispatchOnce());

        verify(notifications).markFailed(eq(1L), eq("t1"),
                eq(NotificationDispatchService.MISSING_CONFIG_ERROR), isNull(), isNull());
        verify(notifications, never()).markSent(anyLong(), anyString(), any(), any());
    }

    private static FeishuProperties properties(String url) {
        FeishuProperties properties = new FeishuProperties();
        properties.setWebhookUrl(url);
        properties.setMaxPerTick(5);
        properties.setSendingTimeoutMs(60_000);
        properties.setFirstRetryBackoffMs(60_000);
        properties.setSecondRetryBackoffMs(300_000);
        return properties;
    }

    private static NotificationRepository.Claimed claimed(long id, String token, int retryCount) {
        NotificationRecord row = new NotificationRecord(
                id, 1L, "order:1:run:R1:channel:feishu", "R1", "feishu",
                NotificationStatus.PENDING, retryCount, null, null, null,
                "{\"title\":\"【船期变化】PO-1\",\"lines\":[\"MSDU7172233  到港 2026-12-07 → 2026-12-09\"]}",
                OffsetDateTime.parse("2026-11-25T00:00:00Z"), null, null, null);
        return new NotificationRepository.Claimed(row, token);
    }
}
