package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.PlanAdminService;
import com.solovis.entitlement.service.snapshot.SnapshotHolder;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/admin/v1/plans")
public class PlanAdminController {

    private final PlanAdminService service;
    private final SnapshotHolder snapshotHolder;

    public PlanAdminController(PlanAdminService service, SnapshotHolder snapshotHolder) {
        this.service = service;
        this.snapshotHolder = snapshotHolder;
    }

    @GetMapping
    public PlanListResponseDto list() {
        return new PlanListResponseDto(service.list(), snapshotHolder.current().snapshotVersion());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PlanSummaryDto create(@Valid @RequestBody PlanCreateRequest request) { return service.create(request); }

    @GetMapping("/{key}")
    public PlanDetailDto get(@PathVariable String key) { return service.get(key); }

    @PatchMapping("/{key}")
    public PlanSummaryDto patch(@PathVariable String key, @RequestBody PlanPatchRequest request) { return service.patch(key, request); }

    @PostMapping("/{key}/entitlements/preview")
    public PlanPreviewResponseDto preview(@PathVariable String key, @RequestBody PlanEntitlementEditRequest request) {
        return service.preview(key, request);
    }

    @PutMapping("/{key}/entitlements")
    public PlanApplyResponseDto apply(@PathVariable String key, @RequestBody PlanEntitlementEditRequest request) {
        return service.apply(key, request);
    }

    @PostMapping("/{key}/archive")
    public void archive(@PathVariable String key) { service.archive(key); }
}
