package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.service.api.dto.SnapshotDeltaResponseDto;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.store.SnapshotVersionRepository;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
public class DeltaFeedService {

    private static final int MAX_ROWS_PER_REQUEST = 5000;

    private final SnapshotVersionRepository snapshotVersionRepository;
    private final SnapshotHolder snapshotHolder;

    public DeltaFeedService(SnapshotVersionRepository snapshotVersionRepository, SnapshotHolder snapshotHolder) {
        this.snapshotVersionRepository = snapshotVersionRepository;
        this.snapshotHolder = snapshotHolder;
    }

    public SnapshotDeltaResponseDto since(long since) {
        long current = snapshotHolder.current().snapshotVersion();
        if (since > current) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED,
                "Requested 'since' (" + since + ") is ahead of the current version (" + current + "); full resync required.",
                Map.of("currentVersion", current));
        }
        if (since == current) {
            return new SnapshotDeltaResponseDto(1, current, current, java.time.Instant.now().toString(), List.of());
        }
        var rows = snapshotVersionRepository.findSince(since, MAX_ROWS_PER_REQUEST);
        var changes = rows.stream()
            .map(row -> new SnapshotDeltaResponseDto.Change(row.version(), DeltaJson.read(row.deltaJson())))
            .toList();
        long toVersion = changes.isEmpty() ? current : changes.get(changes.size() - 1).version();
        String publishedAt = rows.isEmpty() ? java.time.Instant.now().toString() : rows.get(rows.size() - 1).publishedAt();
        return new SnapshotDeltaResponseDto(1, since, toVersion, publishedAt, changes);
    }
}
