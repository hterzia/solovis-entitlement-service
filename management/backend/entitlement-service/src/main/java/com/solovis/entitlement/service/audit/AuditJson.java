package com.solovis.entitlement.service.audit;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Serialises a DTO for audit_event.before_json/after_json — a record of what the operator saw, not a re-derivable projection. */
public final class AuditJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AuditJson() {}

    public static String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise audit payload: " + value, e);
        }
    }
}
