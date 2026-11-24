package com.shiptrack.collector.msc;

/**
 * One timeline row as plain text. Its milestone is resolved by the status
 * normaliser, not here.
 */
public record RawEvent(String rawText) {
}
