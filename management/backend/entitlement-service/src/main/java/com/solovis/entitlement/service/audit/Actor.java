package com.solovis.entitlement.service.audit;

import java.util.Objects;

/** Who performed a write (data-model.md audit_event.actor_kind/actor_id; c32, c36). */
public record Actor(String id, Kind kind) {

    public enum Kind { PERSON, SYSTEM }

    public Actor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
    }
}
