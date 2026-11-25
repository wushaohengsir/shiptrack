package com.shiptrack.collector.msc;

import com.shiptrack.contract.internal.AgentErrorCode;

/**
 * Result of one attempt against the carrier page.
 */
public sealed interface TrackingOutcome {

    record Success(ParsedTracking parsed, MscPageSnapshot page) implements TrackingOutcome {
    }

    record Failure(AgentErrorCode errorCode, String message) implements TrackingOutcome {

        /**
         * Only transient conditions are retried. A missing container, a captcha
         * or a forced login is a definitive answer for this attempt.
         */
        public boolean retryable() {
            return switch (errorCode) {
                case TIMEOUT, SOURCE_UNAVAILABLE, RATE_LIMITED -> true;
                default -> false;
            };
        }
    }
}
