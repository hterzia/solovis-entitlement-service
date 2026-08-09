package com.solovis.entitlement.client.error;

/**
 * No snapshot could be loaded within {@code startupTimeout} and no usable disk cache existed, or
 * the conformance gate failed at construction.
 *
 * <p>The SDK refuses to guess. Inventing entitlements for accounts it has never seen would be
 * exactly the granting that spec §11 forbids.
 */
public final class EntitlementClientStartupException extends RuntimeException {

    public EntitlementClientStartupException(String message) {
        super(message);
    }

    public EntitlementClientStartupException(String message, Throwable cause) {
        super(message, cause);
    }
}
