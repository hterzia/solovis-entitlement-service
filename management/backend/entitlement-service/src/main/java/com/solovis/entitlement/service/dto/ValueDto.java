package com.solovis.entitlement.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The one value encoding used identically across every API surface (contracts/README.md,
 * "Value encoding"). Every field except `type` is nullable and omitted when unset — the shape
 * a caller sees depends entirely on `type`.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValueDto(
    String type,
    Boolean enabled,
    Long amount,
    Boolean unlimited,
    String tier,
    Integer ordinal
) {}
