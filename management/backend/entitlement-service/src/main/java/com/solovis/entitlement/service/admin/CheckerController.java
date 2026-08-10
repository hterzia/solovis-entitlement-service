package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.api.DecisionController;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.error.RefId;
import com.solovis.entitlement.service.store.AccountOverrideRepository;
import com.solovis.entitlement.service.store.AccountRepository;
import com.solovis.entitlement.service.store.CapabilityRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/v1")
public class CheckerController {

    private final DecisionController decisionController;
    private final AccountOverrideRepository accountOverrideRepository;
    private final AccountRepository accountRepository;
    private final CapabilityRepository capabilityRepository;

    public CheckerController(DecisionController decisionController, AccountOverrideRepository accountOverrideRepository,
            AccountRepository accountRepository, CapabilityRepository capabilityRepository) {
        this.decisionController = decisionController;
        this.accountOverrideRepository = accountOverrideRepository;
        this.accountRepository = accountRepository;
        this.capabilityRepository = capabilityRepository;
    }

    @GetMapping("/check")
    public ResponseEntity<Object> check(
        @RequestParam(required = false) String account, @RequestParam(required = false) String capability,
        @RequestParam(required = false) String override) {
        if (override != null) {
            long id = RefId.parse(override, "ovr_");
            var row = accountOverrideRepository.findById(id)
                .orElseThrow(() -> new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "No override '" + override + "'."));
            var accountRow = accountRepository.findById(row.accountId()).orElseThrow();
            var capRow = capabilityRepository.findById(row.capabilityId()).orElseThrow();
            return noStore(decisionController.single(accountRow.externalId(), capRow.key(), null));
        }
        if (account == null || capability == null) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "Either 'override', or both 'account' and 'capability', are required.");
        }
        return noStore(decisionController.single(account, capability, null));
    }

    /**
     * The decision API sets {@code Cache-Control: max-age=10, stale-if-error=86400} for
     * product-caller reuse; the checker must never let that leak into a browser cache, or an
     * operator's own save could appear stale on their very next re-check (c30).
     */
    private static ResponseEntity<Object> noStore(ResponseEntity<Object> upstream) {
        return ResponseEntity.status(upstream.getStatusCode())
            .headers(h -> {
                h.addAll(upstream.getHeaders());
                h.remove(org.springframework.http.HttpHeaders.CACHE_CONTROL);
            })
            .cacheControl(org.springframework.http.CacheControl.noStore())
            .body(upstream.getBody());
    }
}
