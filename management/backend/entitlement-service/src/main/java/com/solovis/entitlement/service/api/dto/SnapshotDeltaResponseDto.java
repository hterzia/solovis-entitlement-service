package com.solovis.entitlement.service.api.dto;

import com.solovis.entitlement.service.snapshot.DeltaChange;
import java.util.List;

public record SnapshotDeltaResponseDto(int format, long fromVersion, long toVersion, String publishedAt, List<Change> changes) {
    public record Change(long version, DeltaChange change) {}
}
