package com.solovis.entitlement.service.api;

import com.solovis.entitlement.core.conformance.ResolverContract;
import com.solovis.entitlement.service.api.dto.SnapshotDeltaResponseDto;
import com.solovis.entitlement.service.api.dto.SnapshotVersionResponseDto;
import com.solovis.entitlement.service.snapshot.DeltaFeedService;
import com.solovis.entitlement.service.snapshot.FullSnapshotWriter;
import com.solovis.entitlement.service.snapshot.SnapshotHolder;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import java.util.zip.GZIPOutputStream;

@RestController
@RequestMapping("/v1/snapshot")
public class SnapshotFeedController {

    private final SnapshotHolder snapshotHolder;
    private final FullSnapshotWriter fullSnapshotWriter;
    private final DeltaFeedService deltaFeedService;

    public SnapshotFeedController(SnapshotHolder snapshotHolder, FullSnapshotWriter fullSnapshotWriter, DeltaFeedService deltaFeedService) {
        this.snapshotHolder = snapshotHolder;
        this.fullSnapshotWriter = fullSnapshotWriter;
        this.deltaFeedService = deltaFeedService;
    }

    @GetMapping("/version")
    public ResponseEntity<SnapshotVersionResponseDto> version() {
        var snapshot = snapshotHolder.current();
        var body = new SnapshotVersionResponseDto(snapshot.snapshotVersion(), java.time.Instant.now().toString(), 1, ResolverContract.VERSION);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }

    @GetMapping(value = "/full", produces = "application/x-ndjson")
    public ResponseEntity<StreamingResponseBody> full() {
        var snapshot = snapshotHolder.current();
        StreamingResponseBody body = out -> {
            try (var gzip = new GZIPOutputStream(out)) {
                fullSnapshotWriter.write(snapshot, gzip);
            }
        };
        return ResponseEntity.ok().header("Content-Encoding", "gzip")
            .contentType(MediaType.parseMediaType("application/x-ndjson")).body(body);
    }

    @GetMapping
    public SnapshotDeltaResponseDto delta(@RequestParam long since) {
        return deltaFeedService.since(since);
    }
}
