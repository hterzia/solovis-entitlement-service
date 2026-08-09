package com.solovis.entitlement.service.audit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** No authentication in v1 (plan.md "Accepted deviations") — every write is attributed to one configured identity. */
@Component
public class StubActorResolver implements ActorResolver {

    private final Actor actor;

    public StubActorResolver(@Value("${entitlement.actor.id:dev-operator}") String actorId) {
        this.actor = new Actor(actorId, Actor.Kind.PERSON);
    }

    @Override
    public Actor currentActor() {
        return actor;
    }
}
