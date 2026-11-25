package com.shiptrack.api.service;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * §7.5 client-side rate limit: 5 calls/second and 100 calls/minute. A denied
 * acquire leaves the notification {@code pending}; the next tick retries it.
 */
public class FeishuRateLimiter {

    private final Deque<Long> attempts = new ArrayDeque<>();

    public synchronized boolean tryAcquire(int perSecond, int perMinute) {
        long now = System.currentTimeMillis();
        while (!attempts.isEmpty() && now - attempts.peekFirst() >= 60_000L) {
            attempts.pollFirst();
        }
        int lastSecond = 0;
        for (long at : attempts) {
            if (now - at < 1_000L) {
                lastSecond++;
            }
        }
        if (attempts.size() >= perMinute || lastSecond >= perSecond) {
            return false;
        }
        attempts.addLast(now);
        return true;
    }
}
