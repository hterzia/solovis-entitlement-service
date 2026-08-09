package com.solovis.entitlement.core.model;

/** A capability's value shape. Immutable across every plan (c1) — enforced by having no setter, only reconstruction. */
public enum ValueType {
    SWITCH,
    QUANTITY,
    TIER
}
