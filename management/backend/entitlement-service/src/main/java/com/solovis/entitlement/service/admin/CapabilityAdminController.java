package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.snapshot.SnapshotHolder;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/v1/capabilities")
public class CapabilityAdminController {

    private final CapabilityAdminService service;
    private final SnapshotHolder snapshotHolder;

    public CapabilityAdminController(CapabilityAdminService service, SnapshotHolder snapshotHolder) {
        this.service = service;
        this.snapshotHolder = snapshotHolder;
    }

    @GetMapping
    public CapabilityListResponseDto list(
        @RequestParam(required = false) String area,
        @RequestParam(required = false, defaultValue = "ACTIVE") String status,
        @RequestParam(required = false) String q) {
        return new CapabilityListResponseDto(service.list(area, status, q), snapshotHolder.current().snapshotVersion());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public com.solovis.entitlement.service.dto.CapabilityDescriptorDto create(@Valid @RequestBody CapabilityCreateRequest request) {
        return service.create(request);
    }

    @GetMapping("/{key}")
    public CapabilityDetailResponseDto get(@PathVariable String key) {
        return service.get(key);
    }

    @PatchMapping("/{key}")
    public com.solovis.entitlement.service.dto.CapabilityDescriptorDto patch(
        @PathVariable String key, @RequestBody CapabilityPatchRequest request) {
        return service.patch(key, request);
    }

    @PostMapping("/{key}/tiers")
    public com.solovis.entitlement.service.dto.CapabilityDescriptorDto appendTier(
        @PathVariable String key, @Valid @RequestBody TierAppendRequest request) {
        return service.appendTier(key, request);
    }

    @PostMapping("/{key}/retire")
    public CapabilityRetireResponseDto retire(@PathVariable String key) {
        return service.retire(key);
    }
}
