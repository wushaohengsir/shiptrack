package com.shiptrack.collector.msc;

/**
 * One attempt to read a container from the carrier site. Implementations must
 * not throw for expected site conditions; they return {@link TrackingOutcome}.
 */
public interface MscTrackingClient {

    TrackingOutcome query(String containerNo);

    /**
     * Queries with an upper bound on the whole attempt. The caller passes the
     * remaining lease, so one slow carrier never runs past the claim.
     */
    default TrackingOutcome query(String containerNo, long budgetMs) {
        return query(containerNo);
    }
}
