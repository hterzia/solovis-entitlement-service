package com.solovis.entitlement.core.model;

/** GRANT raises a capability above its plan; HOLD restricts it below (spec §3.4). */
public enum OverrideKind {
    GRANT,
    HOLD
}
