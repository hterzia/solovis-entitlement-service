package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.AccountAdminService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/v1/accounts")
public class AccountAdminController {

    private final AccountAdminService service;

    public AccountAdminController(AccountAdminService service) { this.service = service; }

    @GetMapping
    public Map<String, Object> search(
        @RequestParam(required = false) String q, @RequestParam(required = false) String planKey,
        @RequestParam(required = false, defaultValue = "0") long cursor,
        @RequestParam(required = false, defaultValue = "50") int limit) {
        return Map.of("accounts", service.search(q, planKey, cursor, limit));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountSummaryDto create(@Valid @RequestBody AccountCreateRequest request) { return service.create(request); }

    @GetMapping("/{external}")
    public AccountDetailDto get(@PathVariable String external) { return service.get(external); }

    @PutMapping("/{external}/plan")
    public PlanReassignResponseDto reassignPlan(@PathVariable String external, @Valid @RequestBody PlanReassignRequest request) {
        return service.reassignPlan(external, request);
    }
}
