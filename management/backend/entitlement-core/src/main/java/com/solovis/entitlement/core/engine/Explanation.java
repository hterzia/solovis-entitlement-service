package com.solovis.entitlement.core.engine;

/**
 * A decision with its full trace (spec §6.1). Produced only by {@link Resolver#explain}, in the
 * management service — see {@code plan.md}, "Recorded interpretations" (c21, c24).
 */
public record Explanation(Decision decision, Trace trace) {}
