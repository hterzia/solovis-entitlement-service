package com.solovis.entitlement.service.api;

import com.solovis.entitlement.core.conformance.ResolverContract;
import com.solovis.entitlement.service.api.dto.SnapshotDeltaResponseDto;
import com.solovis.entitlement.service.api.dto.SnapshotVersionResponseDto;
import com.solovis.entitlement.service.snapshot.DeltaFeedService;
import com.solovis.entitlement.service.snapshot.FullSnapshotWriter;
import com.solovis.entitlement.service.snapshot.SnapshotHolder;
import com.solovis.entitlement.service.store.SnapshotVersionRepository;
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

    private final SnapshotHolder snapshotHolder;
    private final FullSnapshotWriter fullSnapshotWriter;
    private final DeltaFeedService deltaFeedService;
    private final SnapshotVersionRepository snapshotVersionRepository;
    private final Clock clock;

    public SnapshotFeedController(SnapshotHolder snapshotHolder, FullSnapshotWriter fullSnapshotWriter, DeltaFeedService deltaFeedService,
            SnapshotVersionRepository snapshotVersionRepository, Clock clock) {
        this.snapshotHolder = snapshotHolder;
        this.fullSnapshotWriter = fullSnapshotWriter;
        this.deltaFeedService = deltaFeedService;
        this.snapshotVersionRepository = snapshotVersionRepository;
        this.clock = clock;
    }

    @GetMapping("/version")
    public ResponseEntity<SnapshotVersionResponseDto> version() {
        var snapshot = snapshotHolder.current();
        String publishedAt = publishedAtFor(snapshot.snapshotVersion());
        var body = new SnapshotVersionResponseDto(snapshot.snapshotVersion(), publishedAt, 1, ResolverContract.VERSION);
        return ResponseEntity.ok()
            .header(SnapshotVersionHeader.NAME, String.valueOf(snapshot.snapshotVersion()))
            .cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping(value = "/full", produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> full() {
        var snapshot = snapshotHolder.current();
        String publishedAt = publishedAtFor(snapshot.snapshotVersion());
        StreamingResponseBody body = out -> {
            try (var gzip = new GZIPOutputStream(out)) {
                fullSnapshotWriter.write(snapshot, publishedAt, gzip);
            }
        };
        // Stamped from the same immutable snapshot object the body is serialised from, not from a
        // second read of the holder — a write committing mid-stream must not make the header
        // describe a version the body does not contain.
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
        // toVersion, not the holder's current version: the header states the version this response
        // actually carries a replica to, which is lower than current whenever the delta was capped.
        return ResponseEntity.ok()
            .header(SnapshotVersionHeader.NAME, String.valueOf(body.toVersion()))
            .body(body);
    }
}
