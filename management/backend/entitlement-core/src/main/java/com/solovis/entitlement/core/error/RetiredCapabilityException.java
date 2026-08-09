package com.solovis.entitlement.core.error;

/** The capability exists but is retired, so it is not evaluable (c8, c19). */
public final class RetiredCapabilityException extends RuntimeException {

    private final String capabilityKey;

    public RetiredCapabilityException(String capabilityKey) {
        super("Capability '" + capabilityKey + "' is retired and is not evaluable.");
        this.capabilityKey = capabilityKey;
    }

    public String capabilityKey() {
        return capabilityKey;
    }
}
