package com.solovis.entitlement.service.api;

import com.solovis.entitlement.core.engine.*;
import com.solovis.entitlement.core.model.Capability;
import com.solovis.entitlement.core.model.EntitlementValue;
import com.solovis.entitlement.service.api.dto.DecisionResponseDto;
import com.solovis.entitlement.service.dto.ValueMapper;
import java.time.Instant;

public final class DecisionMapper {

    private DecisionMapper() {}

    public static DecisionResponseDto toResponse(Explanation explanation, Capability capability) {
        var decision = explanation.decision();
        return new DecisionResponseDto(
            decision.accountExternalId(), decision.capabilityKey(), decision.allowed(),
            ValueMapper.toDto(decision.value()), decision.snapshotVersion(), decision.evaluatedAt().toString(),
            toTraceDto(explanation.trace(), capability));
    }

    private static DecisionResponseDto.TraceDto toTraceDto(Trace trace, Capability capability) {
        var grants = trace.grants().stream().map(DecisionMapper::toCandidateDto).toList();
        var holds = trace.holds().stream().map(DecisionMapper::toCandidateDto).toList();
        return new DecisionResponseDto.TraceDto(
            toBaselineDto(trace.baseline()), grants, toGrantStepDto(trace), holds, toHoldStepDto(trace),
            new DecisionResponseDto.TraceDto.ResultDto(ValueMapper.toDto(trace.result()), trace.allowed(), allowedReason(trace, capability)));
    }

    private static DecisionResponseDto.TraceDto.BaselineDto toBaselineDto(TraceEntry baseline) {
        String note = baseline.source() == TraceSource.PLAN
            ? "Plan '" + baseline.planKey().orElseThrow() + "' sets this capability."
            : "No plan entitlement is set; the capability default applies.";
        return new DecisionResponseDto.TraceDto.BaselineDto(
            baseline.source().name(), baseline.planKey().orElse(null), ValueMapper.toDto(baseline.value()), note);
    }

    private static DecisionResponseDto.TraceDto.CandidateDto toCandidateDto(TraceEntry entry) {
        return new DecisionResponseDto.TraceDto.CandidateDto(
            entry.overrideId().isPresent() ? "ovr_" + entry.overrideId().getAsLong() : null,
            ValueMapper.toDto(entry.value()), entry.reason().orElse(null), entry.createdBy().orElse(null),
            entry.createdAt().map(Instant::toString).orElse(null), entry.outcome().map(Enum::name).orElse(null));
    }

    private static DecisionResponseDto.TraceDto.GrantStepDto toGrantStepDto(Trace trace) {
        if (trace.grantWinner().isPresent()) {
            var winner = trace.grantWinner().get();
            String note = "Most generous GRANT (" + describe(winner.value()) + ") beats the plan baseline ("
                + describe(trace.baseline().value()) + ").";
            return new DecisionResponseDto.TraceDto.GrantStepDto(true, refOf(winner), ValueMapper.toDto(winner.value()), note, null);
        }
        boolean noGrants = trace.grants().isEmpty();
        String why = noGrants ? "NO_GRANTS" : "PLAN_AT_LEAST_AS_GENEROUS";
        String note = noGrants ? "No GRANT overrides exist for this capability on this account."
            : "The plan baseline is already at least as generous as every GRANT.";
        return new DecisionResponseDto.TraceDto.GrantStepDto(false, null, null, note, why);
    }

    private static DecisionResponseDto.TraceDto.HoldStepDto toHoldStepDto(Trace trace) {
        if (trace.holdWinner().isPresent()) {
            var winner = trace.holdWinner().get();
            String note = "Most restrictive HOLD (" + describe(winner.value()) + ") caps the result.";
            return new DecisionResponseDto.TraceDto.HoldStepDto(true, refOf(winner), ValueMapper.toDto(winner.value()), note, null);
        }
        boolean noHolds = trace.holds().isEmpty();
        String why = noHolds ? "NO_HOLDS" : "HOLD_NOT_MORE_RESTRICTIVE";
        String note = noHolds ? "No HOLD overrides exist for this capability on this account."
            : "No HOLD is more restrictive than the post-grant value.";
        return new DecisionResponseDto.TraceDto.HoldStepDto(false, null, null, note, why);
    }

    private static String allowedReason(Trace trace, Capability capability) {
        var offValue = capability.effectiveOffValue();
        if (offValue.isEmpty()) {
            return "NO_OFF_VALUE_DECLARED";
        }
        return offValue.get().equals(trace.result()) ? "EQUALS_OFF_VALUE" : "DIFFERS_FROM_OFF_VALUE";
    }

    private static String refOf(TraceEntry entry) {
        return "ovr_" + entry.overrideId().getAsLong();
    }

    private static String describe(EntitlementValue value) {
        return switch (value) {
            case EntitlementValue.Switch s -> String.valueOf(s.enabled());
            case EntitlementValue.Quantity q -> q.unlimited() ? "unlimited" : String.valueOf(q.amount());
            case EntitlementValue.Tier t -> t.tierKey();
        };
    }
}
