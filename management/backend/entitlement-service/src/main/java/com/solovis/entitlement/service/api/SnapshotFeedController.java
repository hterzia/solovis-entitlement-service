package com.solovis.entitlement.service.api;

import com.solovis.entitlement.core.conformance.ResolverContract;
import com.solovis.entitlement.service.api.dto.SnapshotDeltaResponseDto;
import com.solovis.entitlement.service.api.dto.SnapshotVersionResponseDto;
import com.solovis.entitlement.service.snapshot.DeltaFeedService;
import com.solovis.entitlement.service.snapshot.FullSnapshotWriter;
import com.solovis.entitlement.service.store.SnapshotVersionRepository;
import com.solovis.entitlement.service.store.SnapshotVersionRow;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.time.Clock;
import com.solovis.entitlement.service.time.Timestamps;
import java.util.zip.GZIPOutputStream;

@RestController
@RequestMapping("/v1/snapshot")
public class SnapshotFeedController {

    private final FullSnapshotWriter fullSnapshotWriter;
    private final DeltaFeedService deltaFeedService;
    private final SnapshotVersionRepository snapshotVersionRepository;
    private final Clock clock;

    public SnapshotFeedController(FullSnapshotWriter fullSnapshotWriter, DeltaFeedService deltaFeedService,
            SnapshotVersionRepository snapshotVersionRepository, Clock clock) {
        this.fullSnapshotWriter = fullSnapshotWriter;
        this.deltaFeedService = deltaFeedService;
        this.snapshotVersionRepository = snapshotVersionRepository;
        this.clock = clock;
    }

    @GetMapping("/version")
    public ResponseEntity<SnapshotVersionResponseDto> version() {
        // Version and publishedAt come from the same row, so the pair can never describe two
        // different moments; the clock fallback is only for a genuinely empty table.
        var latest = snapshotVersionRepository.findLatest();
        long version = latest.map(SnapshotVersionRow::version).orElse(0L);
        String publishedAt = latest.map(SnapshotVersionRow::publishedAt)
            .orElseGet(() -> Timestamps.iso(clock.instant()));
        var body = new SnapshotVersionResponseDto(version, publishedAt, 1, ResolverContract.VERSION);
        return ResponseEntity.ok()
            .header(SnapshotVersionHeader.NAME, String.valueOf(version))
            .cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping(value = "/full", produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> full() {
        // Assembled inside one read transaction, so the body describes a version that actually
        // existed; the object is immutable, so streaming it after that transaction closes is free.
        var snapshot = deltaFeedService.fullSnapshot();
        String publishedAt = publishedAtFor(snapshot.snapshotVersion());
        StreamingResponseBody body = out -> {
            try (var gzip = new GZIPOutputStream(out)) {
                fullSnapshotWriter.write(snapshot, publishedAt, gzip);
            }
        };
        // Stamped from the same immutable snapshot object the body is serialised from — a write
        // committing mid-stream must not make the header describe a version the body does not carry.
        return ResponseEntity.ok().header("Content-Encoding", "gzip")
            .header(SnapshotVersionHeader.NAME, String.valueOf(snapshot.snapshotVersion()))
            .contentType(MediaType.parseMediaType("application/x-ndjson")).body(body);
    }

    /** The recorded publish time for a version — same resolution {@code /version} and {@code /full} must agree on. */
    private String publishedAtFor(long version) {
        return snapshotVersionRepository.findByVersion(version)
            .map(row -> row.publishedAt())
            .orElseGet(() -> Timestamps.iso(clock.instant()));
    }

    @GetMapping
    public ResponseEntity<SnapshotDeltaResponseDto> delta(@RequestParam long since) {
        var body = deltaFeedService.since(since);
        // toVersion, not the current version: the header states the version this response actually
        // carries a replica to, which is lower than current whenever the delta was capped.
        return ResponseEntity.ok()
            .header(SnapshotVersionHeader.NAME, String.valueOf(body.toVersion()))
            .body(body);
    }
}
