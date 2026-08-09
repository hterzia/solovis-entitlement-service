package com.solovis.entitlement.service.error;

import com.solovis.entitlement.core.error.UnknownAccountException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A throwaway controller that only exists to exercise {@link GlobalExceptionHandler} in
 * {@link GlobalExceptionHandlerTest}. Kept as a top-level class rather than nested inside the
 * test: Spring Boot's {@code TestTypeExcludeFilter} unconditionally excludes classes nested
 * inside a test class from {@code @WebMvcTest} bean scanning, even when named explicitly via
 * {@code controllers = ...}.
 */
@RestController
class ThrowingController {

    @GetMapping("/test/unknown-account")
    void unknownAccount() {
        throw new UnknownAccountException("acct_missing");
    }

    @GetMapping("/test/reason-required")
    void reasonRequired() {
        throw new EntitlementApiException(ErrorCode.REASON_REQUIRED, "Reason is required.");
    }
}
