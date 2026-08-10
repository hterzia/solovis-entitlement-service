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
    LOST_NOT_MORE_RESTRICTIVE_THAN_WINNING_HOLD,

    /**
     * The three below mean the override took no part at all, rather than taking part and losing
     * (002 c19). They are named separately because *"it lost to a bigger grant"* and *"it ended on
     * 30 June"* are different answers to *"why isn't this customer getting it?"*, and only the
     * second explains an answer that changed with nobody acting.
     */
    NOT_IN_FORCE_PENDING,
    NOT_IN_FORCE_ENDED,
    NOT_IN_FORCE_REMOVED;

    /** True for the outcomes that describe an override which never entered the arithmetic. */
    public boolean isNotInForce() {
        return this == NOT_IN_FORCE_PENDING || this == NOT_IN_FORCE_ENDED || this == NOT_IN_FORCE_REMOVED;
    }
}
