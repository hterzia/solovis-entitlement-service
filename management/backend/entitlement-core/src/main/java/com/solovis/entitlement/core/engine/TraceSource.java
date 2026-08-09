package com.solovis.entitlement.core.engine;

/** Where a trace entry's baseline value came from — distinguishes a defaulted 0 from a plan 0 (c22). */
public enum TraceSource {
    CAPABILITY_DEFAULT,
    PLAN
}
