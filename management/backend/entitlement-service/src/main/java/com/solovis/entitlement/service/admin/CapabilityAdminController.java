package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.dto.CapabilityDescriptorDto;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.store.DecisionReadDao;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/v1/capabilities")
public class CapabilityAdminController {

    private final CapabilityAdminService service;
    private final DecisionReadDao decisionReadDao;

    public CapabilityAdminController(CapabilityAdminService service, DecisionReadDao decisionReadDao) {
        this.service = service;
        this.decisionReadDao = decisionReadDao;
    }

    @GetMapping
    public Object list(
        @RequestParam(required = false) String area,
        @RequestParam(required = false, defaultValue = "ACTIVE") String status,
        @RequestParam(required = false) String q,
        @RequestParam(required = false) String groupBy) {
        List<CapabilityDescriptorDto> capabilities = service.list(area, status, q);
        long snapshotVersion = decisionReadDao.latestVersion();
        if (groupBy == null) {
            return new CapabilityListResponseDto(capabilities, snapshotVersion);
        }
        if (!groupBy.equals("area")) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "groupBy must be 'area'.",
                Map.of("violations", List.of("groupBy must be 'area'.")));
        }
        Map<String, List<CapabilityDescriptorDto>> byArea = new LinkedHashMap<>();
        for (var capability : capabilities) {
            byArea.computeIfAbsent(capability.area(), key -> new java.util.ArrayList<>()).add(capability);
        }
        List<CapabilityAreasResponseDto.AreaGroup> areas = byArea.entrySet().stream()
            .map(entry -> new CapabilityAreasResponseDto.AreaGroup(entry.getKey(), entry.getValue()))
            .toList();
        return new CapabilityAreasResponseDto(areas, snapshotVersion);
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
