package com.solovis.entitlement.service.admin;

import com.solovis.entitlement.service.admin.service.AsAtCheckService;
import com.solovis.entitlement.service.api.DecisionController;
import com.solovis.entitlement.service.error.EntitlementApiException;
import com.solovis.entitlement.service.error.ErrorCode;
import com.solovis.entitlement.service.error.RefId;
import com.solovis.entitlement.service.store.AccountOverrideRepository;
import com.solovis.entitlement.service.store.AccountRepository;
import com.solovis.entitlement.service.store.CapabilityRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/admin/v1")
public class CheckerController {

    private final DecisionController decisionController;
    private final AccountOverrideRepository accountOverrideRepository;
    private final AccountRepository accountRepository;
    private final CapabilityRepository capabilityRepository;
    private final AsAtCheckService asAtCheckService;

    public CheckerController(DecisionController decisionController, AccountOverrideRepository accountOverrideRepository,
            AccountRepository accountRepository, CapabilityRepository capabilityRepository,
            AsAtCheckService asAtCheckService) {
        this.decisionController = decisionController;
        this.accountOverrideRepository = accountOverrideRepository;
        this.accountRepository = accountRepository;
        this.capabilityRepository = capabilityRepository;
        this.asAtCheckService = asAtCheckService;
    }

    /**
     * The operator checker. Two lookup modes — by {@code override}, or by {@code account} plus
     * {@code capability} — and an optional {@code asAt} date on either.
     *
     * <p>{@code asAt} threads through the mode resolution rather than forking it, so the two modes
     * cannot answer differently about the same past day. It is deliberately absent from {@code /v1}:
     * the past is an operator surface (§6.2), and a past answer costs several indexed audit reads
     * that have no business on a product's request path.
     */
    @GetMapping("/check")
    public ResponseEntity<Object> check(
        @RequestParam(required = false) String account, @RequestParam(required = false) String capability,
        @RequestParam(required = false) String override, @RequestParam(required = false) String asAt) {
        String resolvedAccount = account;
        String resolvedCapability = capability;
        if (override != null) {
            long id = RefId.parse(override, "ovr_");
            var row = accountOverrideRepository.findById(id)
                .orElseThrow(() -> new EntitlementApiException(ErrorCode.VALIDATION_FAILED, "No override '" + override + "'."));
            resolvedAccount = accountRepository.findById(row.accountId()).orElseThrow().externalId();
            resolvedCapability = capabilityRepository.findById(row.capabilityId()).orElseThrow().key();
        } else if (account == null || capability == null) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED,
                "Either 'override', or both 'account' and 'capability', are required.");
        }

        if (asAt == null || asAt.isBlank()) {
            return noStore(decisionController.single(resolvedAccount, resolvedCapability, null));
        }
        return noStore(ResponseEntity.ok(asAtCheckService.check(resolvedAccount, resolvedCapability, parseDate(asAt))));
    }

    private static LocalDate parseDate(String value) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new EntitlementApiException(ErrorCode.VALIDATION_FAILED,
                "'asAt' must be an ISO date like 2026-03-14, not '" + value + "'.");
        }
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
