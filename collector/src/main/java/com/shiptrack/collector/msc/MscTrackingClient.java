package com.shiptrack.collector.msc;

/**
 * One attempt to read a container from the carrier site. Implementations must
 * not throw for expected site conditions; they return {@link TrackingOutcome}.
 */
public interface MscTrackingClient {

    TrackingOutcome query(String containerNo);
}
