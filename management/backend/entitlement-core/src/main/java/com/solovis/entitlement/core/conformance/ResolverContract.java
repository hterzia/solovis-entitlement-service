package com.solovis.entitlement.core.conformance;

/**
 * The resolution semantics of spec §4, versioned separately from the wire format (research.md
 * §20). Bump only when the rule itself changes — {@code future-spec.md} §1 (time-bounded
 * overrides) and §5 (relative grants) both would.
 */
public final class ResolverContract {

    public static final int VERSION = 1;

    private ResolverContract() {}
}
