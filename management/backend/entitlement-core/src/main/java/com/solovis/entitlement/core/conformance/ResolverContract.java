package com.solovis.entitlement.core.conformance;

/**
 * The resolution semantics of spec §4, versioned separately from the wire format (research.md
 * §20). Bump only when the rule itself changes.
 *
 * <p><strong>Time-bounded overrides (002) do not.</strong> This javadoc, and the matching note in
 * contracts/README.md, previously named them as a reason to bump — on the assumption that replicas
 * would evaluate windows themselves. They must not: 002 c14 requires a cut-off product to go on
 * honouring an override that has ended, and a replica able to evaluate its own windows would lapse
 * <em>correctly</em> while disconnected, which is exactly what the fixed outage posture forbids.
 *
 * <p>So windows are evaluated only where the record lives, before publication. A beginning reaches
 * a replica as an ordinary {@code override.created} and an ending as {@code override.removed},
 * {@code resolve()} is unchanged, and no coordinated release across products is needed. Relative
 * grants would still bump this, because they genuinely redefine "most generous".
 */
public final class ResolverContract {

    public static final int VERSION = 1;

    private ResolverContract() {}
}
