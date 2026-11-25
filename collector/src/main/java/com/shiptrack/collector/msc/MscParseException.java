package com.shiptrack.collector.msc;

/**
 * The page loaded but its content could not be turned into a tracking result.
 */
public class MscParseException extends RuntimeException {

    public MscParseException(String message) {
        super(message);
    }
}
