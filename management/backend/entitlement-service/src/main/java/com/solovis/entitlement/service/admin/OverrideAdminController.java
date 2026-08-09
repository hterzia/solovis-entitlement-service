package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.OverrideCreateRequest;
import com.solovis.entitlement.service.admin.dto.OverrideMutationResponseDto;
import com.solovis.entitlement.service.admin.service.OverrideAdminService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/v1/accounts/{external}/overrides")
public class OverrideAdminController {

    private final OverrideAdminService service;

    public OverrideAdminController(OverrideAdminService service) { this.service = service; }

    public record DeleteRequest(String reason) {}

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OverrideMutationResponseDto create(@PathVariable String external, @Valid @RequestBody OverrideCreateRequest request) {
        return service.create(external, request);
    }

    @DeleteMapping("/{id}")
    public OverrideMutationResponseDto delete(@PathVariable String external, @PathVariable String id,
            @RequestBody(required = false) DeleteRequest body) {
        return service.delete(external, id, body == null ? null : body.reason());
    }
}
