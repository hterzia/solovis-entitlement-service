package com.solovis.entitlement.service.audit;

/**
 * The auth seam (research.md §14). v1 ships only {@link StubActorResolver}; swapping in OIDC
 * later is a bean replacement, not a retrofit through every write path.
 */
public interface ActorResolver {
    Actor currentActor();
}
