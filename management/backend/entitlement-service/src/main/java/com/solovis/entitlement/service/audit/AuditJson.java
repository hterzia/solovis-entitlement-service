package com.solovis.entitlement.service.audit;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Serialises a DTO for audit_event.before_json/after_json — a record of what the operator saw, not a re-derivable projection. */
@Component
public final class AuditJson {

    private final ObjectMapper mapper;

    public AuditJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise audit payload: " + value, e);
        }
    }
}
