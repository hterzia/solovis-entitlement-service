package com.solovis.entitlement.service.api.dto;

import com.solovis.entitlement.service.snapshot.DeltaChange;
import tools.jackson.databind.annotation.JsonSerialize;
import java.util.List;

public record SnapshotDeltaResponseDto(int format, long fromVersion, long toVersion, String publishedAt, List<Change> changes) {

    /**
     * {@code version} plus every field of {@code change}, flattened into one JSON object at the same
     * level (snapshot-feed.md §3's own example, e.g. {@code {"version":48209,"kind":"plan.entitlements",...}}).
     * {@code @JsonUnwrapped} can't do this here — Jackson 3 refuses to emit a type-id-bearing
     * polymorphic value ({@link DeltaChange}'s {@code kind} discriminator) once unwrapped — so
     * {@link ChangeSerializer} does the flattening by hand via a tree merge.
     */
    @JsonSerialize(using = ChangeSerializer.class)
    public record Change(long version, DeltaChange change) {}
}
