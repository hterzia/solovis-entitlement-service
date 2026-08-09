package com.solovis.entitlement.core.error;

/** No capability is declared with this key (c19) — an error, never a denial. */
public final class UnknownCapabilityException extends RuntimeException {

    private final String capabilityKey;

    public UnknownCapabilityException(String capabilityKey) {
        super("No capability is declared with key '" + capabilityKey + "'.");
        this.capabilityKey = capabilityKey;
    }

    public String capabilityKey() {
        return capabilityKey;
    }
}
