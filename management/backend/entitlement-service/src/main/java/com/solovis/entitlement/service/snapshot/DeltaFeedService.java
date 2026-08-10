package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.view.Snapshot;
import com.solovis.entitlement.service.api.dto.SnapshotDeltaResponseDto;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.store.DecisionReadDao;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import com.solovis.entitlement.service.time.Timestamps;
import java.util.List;
import java.util.Map;

@Component
public class DeltaFeedService {

    private static final int MAX_ROWS_PER_REQUEST = 5000;

    private final DecisionReadDao decisionReadDao;
    private final SnapshotAssembler snapshotAssembler;
    private final Clock clock;

    public DeltaFeedService(DecisionReadDao decisionReadDao, SnapshotAssembler snapshotAssembler, Clock clock) {
        this.decisionReadDao = decisionReadDao;
        this.snapshotAssembler = snapshotAssembler;
        this.clock = clock;
    }

    @Transactional(transactionManager = "entitlementReadTransactionManager", readOnly = true)
    public SnapshotDeltaResponseDto since(long since) {
        long current = decisionReadDao.latestVersion();
        if (since > current) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED,
                "Requested 'since' (" + since + ") is ahead of the current version (" + current + "); full resync required.",
                Map.of("currentVersion", current));
        }
        if (since == current) {
            return new SnapshotDeltaResponseDto(1, current, current, Timestamps.iso(clock.instant()), List.of());
        }
        var rows = decisionReadDao.findSince(since, MAX_ROWS_PER_REQUEST);
        if (rows.isEmpty() || rows.get(0).version() != since + 1) {
            throw new EntitlementApiException(ErrorCode.SNAPSHOT_TOO_OLD,
                "Versions after " + since + " are no longer retained; a full resync is required.",
                Map.of("currentVersion", current));
        }
        var changes = rows.stream()
            .map(row -> new SnapshotDeltaResponseDto.Change(row.version(), DeltaJson.read(row.deltaJson())))
            .toList();
        long toVersion = changes.get(changes.size() - 1).version();
        String publishedAt = rows.get(rows.size() - 1).publishedAt();
        return new SnapshotDeltaResponseDto(1, since, toVersion, publishedAt, changes);
    }

    @Transactional(transactionManager = "entitlementReadTransactionManager", readOnly = true)
    public Snapshot fullSnapshot() {
        return snapshotAssembler.assembleFull();
    }
}
