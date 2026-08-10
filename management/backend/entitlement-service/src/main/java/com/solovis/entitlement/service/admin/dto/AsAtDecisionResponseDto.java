package com.solovis.entitlement.service.admin.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.solovis.entitlement.service.api.dto.DecisionResponseDto;

/**
 * A past answer: the ordinary decision payload, plus the date it describes.
 *
 * <p>The decision is unwrapped so the body is the {@code /v1} shape with two extra fields rather
 * than a nested variant of it — the checker screen renders one thing whether it is showing today or
 * March, and {@code <TraceView>} must not need a second code path (ui-screens.md).
 *
 * <p>{@code capabilityRetiredSince} is what makes c28 an answer rather than an error: a capability
 * retired since the date asked about resolves normally and says so, which is deliberately unlike
 * asking about a retired capability <em>today</em> — that stays the v1
 * {@code entitlement/retired-capability} error, because "no longer evaluable" and "was evaluable
 * then" are different facts.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AsAtDecisionResponseDto(
    String asAt,
    @JsonUnwrapped DecisionResponseDto decision,
    String capabilityRetiredSince
) {}
