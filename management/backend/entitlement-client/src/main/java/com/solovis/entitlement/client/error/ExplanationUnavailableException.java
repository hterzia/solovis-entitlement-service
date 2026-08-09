package com.solovis.entitlement.client.error;

/**
 * {@code explain()} could not reach the service. Diagnostic path only — never thrown by
 * {@code check} or {@code checkAll}, which answer from the replica regardless of the service.
 */
public final class ExplanationUnavailableException extends RuntimeException {

    public ExplanationUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
