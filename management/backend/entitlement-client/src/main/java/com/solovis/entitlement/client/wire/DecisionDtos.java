package com.solovis.entitlement.client.wire;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.solovis.entitlement.core.engine.Decision;
import com.solovis.entitlement.core.engine.Explanation;
import com.solovis.entitlement.core.engine.Outcome;
import com.solovis.entitlement.core.engine.Trace;
import com.solovis.entitlement.core.engine.TraceEntry;
import com.solovis.entitlement.core.engine.TraceSource;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * The decision + trace wire shape, mirroring {@code service.api.dto.DecisionResponseDto} exactly
 * (including the nested trace records) so this module never redefines a wire shape the service
 * already owns.
 *
 * <p>Deserialised on two paths only: the diagnostic {@link #toExplanation} path ({@code explain()}
 * always calls the service) and the read-through {@link #toDecision} path ({@code check()} on an
 * account the replica does not know). This SDK never builds a {@link Trace} any other way — it has
 * no trace data of its own to build one from.
 */
public final class DecisionDtos {

    private DecisionDtos() {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DecisionResponse(
        String account, String capability, boolean allowed, ValueDto value,
        long snapshotVersion, String evaluatedAt, TraceDto trace) {

        public record TraceDto(
            BaselineDto baseline, List<CandidateDto> grants, GrantStepDto grantStep,
            List<CandidateDto> holds, HoldStepDto holdStep, ResultDto result) {

            public record BaselineDto(String source, String planKey, ValueDto value, String note) {}

            public record CandidateDto(
                String overrideId, ValueDto value, String reason, String createdBy, String createdAt,
                String outcome) {}

            public record GrantStepDto(boolean applied, String winner, ValueDto value, String note, String why) {}

            public record HoldStepDto(boolean applied, String winner, ValueDto value, String note, String why) {}

            public record ResultDto(ValueDto value, boolean allowed, String allowedReason) {}
        }
    }

    public static Decision toDecision(DecisionResponse response) {
        return new Decision(
            response.account(), response.capability(), response.allowed(),
            WireMapper.toValue(response.value()), response.snapshotVersion(),
            Instant.parse(response.evaluatedAt()));
    }

    public static Explanation toExplanation(DecisionResponse response) {
        return new Explanation(toDecision(response), toTrace(response.trace()));
    }

    private static Trace toTrace(DecisionResponse.TraceDto trace) {
        var grants = trace.grants().stream().map(c -> toCandidateEntry(c, TraceSource.GRANT)).toList();
        var holds = trace.holds().stream().map(c -> toCandidateEntry(c, TraceSource.HOLD)).toList();
        return new Trace(
            toBaselineEntry(trace.baseline()),
            grants,
            winnerOf(grants),
            holds,
            winnerOf(holds),
            WireMapper.toValue(trace.result().value()),
            trace.result().allowed());
    }

    /**
     * The winner of a candidate group is whichever entry's outcome is {@code WON} — never the
     * step's {@code applied}/{@code winner} fields, which are presentation-only. An empty result
     * covers both "no candidates" and "candidates existed but none won" identically, matching
     * {@link Trace}'s own contract.
     */
    private static Optional<TraceEntry> winnerOf(List<TraceEntry> candidates) {
        return candidates.stream().filter(c -> c.outcome().equals(Optional.of(Outcome.WON))).findFirst();
    }

    private static TraceEntry toBaselineEntry(DecisionResponse.TraceDto.BaselineDto baseline) {
        return new TraceEntry(
            TraceSource.valueOf(baseline.source()),
            OptionalLong.empty(),
            Optional.ofNullable(baseline.planKey()),
            WireMapper.toValue(baseline.value()),
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static TraceEntry toCandidateEntry(DecisionResponse.TraceDto.CandidateDto candidate, TraceSource source) {
        return new TraceEntry(
            source,
            candidate.overrideId() == null ? OptionalLong.empty() : OptionalLong.of(WireMapper.refToId(candidate.overrideId())),
            Optional.empty(),   // planKey is a baseline-only field
            WireMapper.toValue(candidate.value()),
            Optional.ofNullable(candidate.reason()),
            Optional.ofNullable(candidate.createdBy()),
            candidate.createdAt() == null ? Optional.empty() : Optional.of(Instant.parse(candidate.createdAt())),
            candidate.outcome() == null ? Optional.empty() : Optional.of(Outcome.valueOf(candidate.outcome())));
    }
}
