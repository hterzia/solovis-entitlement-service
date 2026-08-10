package com.solovis.entitlement.service.api;

import com.solovis.entitlement.core.engine.Resolver;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.view.Snapshot;
import com.solovis.entitlement.service.api.dto.CapabilityListResponseDto;
import com.solovis.entitlement.service.api.dto.WholeAccountResponseDto;
import com.solovis.entitlement.service.dto.CapabilityDescriptorMapper;
import com.solovis.entitlement.service.dto.ValueMapper;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.snapshot.SnapshotHolder;
import com.solovis.entitlement.core.error.UnknownAccountException;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Clock;
import java.time.Instant;
import com.solovis.entitlement.service.time.Timestamps;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;

@RestController
@RequestMapping("/v1")
public class DecisionController {

    private final SnapshotHolder snapshotHolder;
    private final Clock clock;

    public DecisionController(SnapshotHolder snapshotHolder, Clock clock) {
        this.snapshotHolder = snapshotHolder;
        this.clock = clock;
    }

    @GetMapping("/accounts/{accountExternalId}/capabilities/{capabilityKey}")
    public ResponseEntity<Object> single(
        @PathVariable String accountExternalId, @PathVariable String capabilityKey,
        @RequestParam(required = false) Long minSnapshotVersion) {
        Snapshot snapshot = snapshotAtLeast(minSnapshotVersion);
        var key = parseKey(capabilityKey);
        var explanation = Resolver.explain(snapshot, accountExternalId, key, clock.instant());
        var capability = snapshot.capability(key).orElseThrow();
        var body = DecisionMapper.toResponse(explanation, capability);
        return ResponseEntity.ok()
            .header(SnapshotVersionHeader.NAME, String.valueOf(snapshot.snapshotVersion()))
            .cacheControl(CacheControl.maxAge(Duration.ofSeconds(10)).staleIfError(Duration.ofHours(24)))
            .body(body);
    }

    @GetMapping("/accounts/{accountExternalId}/entitlements")
    public ResponseEntity<WholeAccountResponseDto> whole(@PathVariable String accountExternalId) {
        Snapshot snapshot = snapshotHolder.current();
        var account = snapshot.account(accountExternalId).orElseThrow(() -> new UnknownAccountException(accountExternalId));
        Instant evaluatedAt = clock.instant();
        var entitlements = snapshot.activeCapabilities().stream()
            .sorted(Comparator.comparing(c -> c.key().value()))
            .map(capability -> {
                var decision = Resolver.resolve(snapshot, accountExternalId, capability.key(), evaluatedAt);
                return new WholeAccountResponseDto.Entitlement(capability.key().value(), decision.allowed(), ValueMapper.toDto(decision.value()));
            }).toList();
        var body = new WholeAccountResponseDto(accountExternalId, account.planKey(), snapshot.snapshotVersion(),
            Timestamps.iso(evaluatedAt), entitlements);
        return ResponseEntity.ok().header(SnapshotVersionHeader.NAME, String.valueOf(snapshot.snapshotVersion())).body(body);
    }

    @GetMapping("/capabilities")
    public ResponseEntity<CapabilityListResponseDto> list(
        @RequestParam(required = false) String area,
        @RequestParam(required = false, defaultValue = "ACTIVE") String status) {
        if (!status.equals("ACTIVE") && !status.equals("RETIRED") && !status.equals("ALL")) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "status must be one of ACTIVE, RETIRED, ALL",
                Map.of("violations", java.util.List.of("status: must be one of ACTIVE, RETIRED, ALL")));
        }
        Snapshot snapshot = snapshotHolder.current();
        var stream = status.equals("ALL") ? snapshot.capabilities().stream()
            : status.equals("RETIRED") ? snapshot.capabilities().stream().filter(c -> c.isRetired())
            : snapshot.activeCapabilities().stream();
        if (area != null) {
            stream = stream.filter(c -> c.area().equals(area));
        }
        var descriptors = stream.sorted(Comparator.comparing(c -> c.key().value()))
            .map(CapabilityDescriptorMapper::toDescriptor).toList();
        var body = new CapabilityListResponseDto(descriptors, snapshot.snapshotVersion());
        return ResponseEntity.ok()
            .header(SnapshotVersionHeader.NAME, String.valueOf(snapshot.snapshotVersion()))
            .body(body);
    }

    @GetMapping("/capabilities/{capabilityKey}")
    public ResponseEntity<com.solovis.entitlement.service.dto.CapabilityDescriptorDto> one(@PathVariable String capabilityKey) {
        Snapshot snapshot = snapshotHolder.current();
        var capability = snapshot.capability(parseKey(capabilityKey))
            .orElseThrow(() -> new com.solovis.entitlement.core.error.UnknownCapabilityException(capabilityKey));
        var body = CapabilityDescriptorMapper.toDescriptor(capability);
        return ResponseEntity.ok()
            .header(SnapshotVersionHeader.NAME, String.valueOf(snapshot.snapshotVersion()))
            .body(body);
    }

    private static CapabilityKey parseKey(String raw) {
        try {
            return new CapabilityKey(raw);
        } catch (IllegalArgumentException e) {
            throw new com.solovis.entitlement.core.error.UnknownCapabilityException(raw);
        }
    }

    private Snapshot snapshotAtLeast(Long minSnapshotVersion) {
        Snapshot snapshot = snapshotHolder.current();
        if (minSnapshotVersion != null && snapshot.snapshotVersion() < minSnapshotVersion) {
            throw new EntitlementApiException(ErrorCode.SNAPSHOT_BEHIND,
                "Current snapshot version " + snapshot.snapshotVersion() + " is behind the requested " + minSnapshotVersion + ".",
                Map.of("currentVersion", snapshot.snapshotVersion()));
        }
        return snapshot;
    }
}
