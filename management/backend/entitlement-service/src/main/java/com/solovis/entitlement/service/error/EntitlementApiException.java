package com.solovis.entitlement.service.error;

import java.util.LinkedHashMap;
import java.util.Map;

/** The one exception type every service/controller in this codebase throws for a contract-defined error. */
public class EntitlementApiException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> extraProperties;

    public EntitlementApiException(ErrorCode errorCode, String detail) {
        this(errorCode, detail, Map.of());
    }

    public EntitlementApiException(ErrorCode errorCode, String detail, Map<String, Object> extraProperties) {
        super(detail);
        this.errorCode = errorCode;
        this.extraProperties = new LinkedHashMap<>(extraProperties);
    }

    public ErrorCode errorCode() { return errorCode; }
    public Map<String, Object> extraProperties() { return extraProperties; }
}
