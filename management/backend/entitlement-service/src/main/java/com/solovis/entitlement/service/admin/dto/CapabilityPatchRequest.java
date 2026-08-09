package com.solovis.entitlement.service.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.solovis.entitlement.service.dto.ValueDto;

// Note the absence of valueType — a capability's declared type is immutable after creation (c1);
// omitting the field from the request shape is what makes "cannot change" structural, not enforced by discipline.
public record CapabilityPatchRequest(
    String displayName,
    String description,
    @JsonProperty("default") ValueDto defaultValue,
    ValueDto offValue,
    // Present only so a caller's attempt to change it can be DETECTED (and refused with 409
    // entitlement/immutable-field) rather than silently dropped by Jackson's lenient unknown-
    // property binding. A capability's declared type is immutable after creation (c1).
    String valueType
) {}
