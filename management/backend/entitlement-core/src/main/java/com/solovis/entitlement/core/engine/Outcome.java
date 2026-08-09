package com.solovis.entitlement.core.engine;

/**
 * Why a candidate override did or did not decide the result (c21, c23). Grants have two distinct
 * loss reasons because a grant can lose either to another grant or to the plan itself; a hold has
 * only one, because the most restrictive hold is always marked {@code WON} in its own list even
 * when it does not change the result — {@code Trace.holdWinner} being empty is what records that
 * (decision-api.md, "Ties are deterministic").
 */
public enum Outcome {
    WON,
    LOST_NOT_MORE_GENEROUS_THAN_WINNING_GRANT,
    LOST_NOT_MORE_GENEROUS_THAN_PLAN,
    LOST_NOT_MORE_RESTRICTIVE_THAN_WINNING_HOLD
}
