package com.solovis.entitlement.client.wire;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The three-variant value encoding, mirroring the service's {@code service.dto.ValueDto} exactly.
 * Exactly one variant's fields are populated; the rest are absent, never null-valued.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValueDto(
    String type, Boolean enabled, Long amount, Boolean unlimited, String tier, Integer ordinal) {}
