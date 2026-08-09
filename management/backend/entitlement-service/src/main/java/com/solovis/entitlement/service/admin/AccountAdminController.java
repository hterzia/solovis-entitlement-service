package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.dto.*;
import com.solovis.entitlement.service.admin.service.AccountAdminService;
import com.solovis.entitlement.service.error.RefId;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/v1/accounts")
public class AccountAdminController {

    private final AccountAdminService service;

    public AccountAdminController(AccountAdminService service) { this.service = service; }

    @GetMapping
    public AccountSearchResponseDto search(
        @RequestParam(required = false) String q, @RequestParam(required = false) String planKey,
        @RequestParam(required = false) String cursor,
        @RequestParam(required = false, defaultValue = "50") int limit) {
        long afterId = cursor == null ? 0 : RefId.parse(cursor, "acct_");
        return service.search(q, planKey, afterId, limit);
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
