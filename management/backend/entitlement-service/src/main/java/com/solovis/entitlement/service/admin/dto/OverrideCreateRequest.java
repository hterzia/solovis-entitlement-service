package com.solovis.entitlement.service.admin.dto;

import com.solovis.entitlement.service.dto.ValueDto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * {@code startsOn} and {@code expiresOn} are optional ISO dates ('YYYY-MM-DD') in the service zone,
 * and the expiry day is inclusive (002 spec §3.1). Omitting both is the ordinary case and gives the
 * v1 behaviour: in force from the moment it is saved until someone removes it.
 */
public record OverrideCreateRequest(
    @NotBlank String capability, @NotBlank String kind, @NotNull ValueDto value, @NotBlank String reason,
    String startsOn, String expiresOn
) {

    /** An open-ended override -- the v1 shape, and still the ordinary case. */
    public OverrideCreateRequest(String capability, String kind, ValueDto value, String reason) {
        this(capability, kind, value, reason, null, null);
    }
}
