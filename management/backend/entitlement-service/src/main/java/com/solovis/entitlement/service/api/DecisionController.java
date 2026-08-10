package com.solovis.entitlement.service.api;

import com.solovis.entitlement.service.api.dto.CapabilityListResponseDto;
import com.solovis.entitlement.service.api.dto.WholeAccountResponseDto;
import com.solovis.entitlement.service.dto.CapabilityDescriptorDto;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Duration;

@RestController
@RequestMapping("/v1")
public class DecisionController {

    private final DecisionReadService decisionReadService;

    public DecisionController(DecisionReadService decisionReadService) {
        this.decisionReadService = decisionReadService;
    }

    @GetMapping("/accounts/{accountExternalId}/capabilities/{capabilityKey}")
    public ResponseEntity<Object> single(
        @PathVariable String accountExternalId, @PathVariable String capabilityKey,
        @RequestParam(required = false) Long minSnapshotVersion) {
        var result = decisionReadService.single(accountExternalId, capabilityKey, minSnapshotVersion);
        return ResponseEntity.ok()
            .header("X-Entitlement-Snapshot-Version", String.valueOf(result.snapshotVersion()))
            .cacheControl(CacheControl.maxAge(Duration.ofSeconds(10)).staleIfError(Duration.ofHours(24)))
            .body(result.body());
    }

    @GetMapping("/accounts/{accountExternalId}/entitlements")
    public ResponseEntity<WholeAccountResponseDto> whole(@PathVariable String accountExternalId) {
        var result = decisionReadService.whole(accountExternalId);
        return ResponseEntity.ok().header("X-Entitlement-Snapshot-Version", String.valueOf(result.snapshotVersion())).body(result.body());
    }

    @GetMapping("/capabilities")
    public CapabilityListResponseDto list(
        @RequestParam(required = false) String area,
        @RequestParam(required = false, defaultValue = "ACTIVE") String status) {
        return decisionReadService.capabilityList(area, status);
    }

    @GetMapping("/capabilities/{capabilityKey}")
    public CapabilityDescriptorDto one(@PathVariable String capabilityKey) {
        return decisionReadService.capabilityOne(capabilityKey);
    }
}
