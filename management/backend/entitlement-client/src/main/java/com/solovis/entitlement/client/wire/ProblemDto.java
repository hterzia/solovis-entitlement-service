package com.solovis.entitlement.client.wire;

/**
 * RFC 9457 problem details as the service emits them. Note {@code type} is a bare relative slug
 * ({@code "entitlement/unknown-account"}), not an absolute URI, and that extra properties are
 * flattened at the top level rather than nested. Branch on {@code type}, never on {@code detail}.
 */
public record ProblemDto(String type, String title, Integer status, String detail, Long currentVersion) {}
