package com.solovis.entitlement.client.transport;

/**
 * The service could not be reached, or answered in a way this SDK cannot use. Always recoverable
 * by backing off and retrying — never a reason to change an answer.
 */
public final class FeedUnavailableException extends RuntimeException {
    public FeedUnavailableException(String message) {
        super(message);
    }
    public FeedUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
