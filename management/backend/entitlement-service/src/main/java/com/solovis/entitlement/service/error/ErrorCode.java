package com.solovis.entitlement.service.error;

import org.springframework.http.HttpStatus;

/** Every `type` slug from contracts/README.md's error model table, paired with its HTTP status. */
public enum ErrorCode {
    UNKNOWN_ACCOUNT("entitlement/unknown-account", HttpStatus.NOT_FOUND, "Unknown account"),
    UNKNOWN_CAPABILITY("entitlement/unknown-capability", HttpStatus.NOT_FOUND, "Unknown capability"),
    RETIRED_CAPABILITY("entitlement/retired-capability", HttpStatus.CONFLICT, "Retired capability"),
    VALUE_TYPE_MISMATCH("entitlement/value-type-mismatch", HttpStatus.UNPROCESSABLE_ENTITY, "Value type mismatch"),
    UNKNOWN_TIER("entitlement/unknown-tier", HttpStatus.UNPROCESSABLE_ENTITY, "Unknown tier"),
    REASON_REQUIRED("entitlement/reason-required", HttpStatus.UNPROCESSABLE_ENTITY, "Reason required"),
    PLAN_IN_USE("entitlement/plan-in-use", HttpStatus.CONFLICT, "Plan in use"),
    DEFAULT_PLAN_REQUIRED("entitlement/default-plan-required", HttpStatus.CONFLICT, "Default plan required"),
    CAPABILITY_RETIRED_FOR_WRITE("entitlement/capability-retired-for-write", HttpStatus.CONFLICT, "Capability retired for write"),
    IMMUTABLE_FIELD("entitlement/immutable-field", HttpStatus.CONFLICT, "Immutable field"),
    SNAPSHOT_TOO_OLD("entitlement/snapshot-too-old", HttpStatus.GONE, "Snapshot too old"),
    SNAPSHOT_BEHIND("entitlement/snapshot-behind", HttpStatus.CONFLICT, "Snapshot behind"),
    VALIDATION_FAILED("entitlement/validation-failed", HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed"),
    PREVIEW_TOKEN_INVALID("entitlement/preview-token-invalid", HttpStatus.CONFLICT, "Preview token invalid or stale"),
    INTERNAL_ERROR("entitlement/internal-error", HttpStatus.INTERNAL_SERVER_ERROR, "Internal error"),
    WRITE_CONFLICT("entitlement/conflict", HttpStatus.CONFLICT, "Conflict"),
    // Same `type` slug as VALIDATION_FAILED (contracts/README.md's error table has no dedicated
    // slug for a uniqueness conflict) but a different HttpStatus — callers branch on `type`, and
    // 409 is the correct status for "key already declared" (admin-api.md, "Capabilities — screen 1").
    DUPLICATE_KEY("entitlement/validation-failed", HttpStatus.CONFLICT, "Validation failed");

    private final String type;
    private final HttpStatus status;
    private final String title;

    ErrorCode(String type, HttpStatus status, String title) {
        this.type = type;
        this.status = status;
        this.title = title;
    }

    public String type() { return type; }
    public HttpStatus status() { return status; }
    public String title() { return title; }
}
