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
    /** 002 c7 — start after expiry, a window wholly in the past, or a back-dated start. */
    INVALID_WINDOW("entitlement/invalid-window", HttpStatus.UNPROCESSABLE_ENTITY, "Invalid override window"),
    // 002 c26/c27. Three refusals rather than one, because "we cannot know" and "you asked something
    // that has no answer" are different facts and an operator acts on them differently. None of the
    // three ever returns a value: a confident wrong answer about the past is worse than none (§6.5).
    /** The date is before this account existed. */
    BEFORE_ACCOUNT_EXISTED("entitlement/before-account-existed", HttpStatus.UNPROCESSABLE_ENTITY, "Before the account existed"),
    /** The date is further back than the change history reaches. */
    BEYOND_HISTORY("entitlement/beyond-history", HttpStatus.UNPROCESSABLE_ENTITY, "Beyond the recorded history"),
    /** The date is in the future; the service reports what was, never what will be. */
    FUTURE_DATE("entitlement/future-date", HttpStatus.UNPROCESSABLE_ENTITY, "Date is in the future"),
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
