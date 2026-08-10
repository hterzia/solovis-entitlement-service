package com.solovis.entitlement.service.admin.service;

import com.solovis.entitlement.core.engine.Resolver;
import com.solovis.entitlement.service.admin.dto.AsAtDecisionResponseDto;
import com.solovis.entitlement.service.api.DecisionMapper;
import com.solovis.entitlement.service.snapshot.AsAtViewAssembler;
import com.solovis.entitlement.service.time.Timestamps;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * What the answer was on a past date (002 c22–c28).
 *
 * <p>Thin on purpose. The reconstruction lives in {@link AsAtViewAssembler} and the arithmetic in
 * the unchanged {@code Resolver.explain()}; this only chooses the moment and dresses the result.
 * That is the property the whole feature rests on — a past answer is the same code as a present
 * one, given a different view, so the two cannot drift apart.
 */
@Service
public class AsAtCheckService {

    private final AsAtViewAssembler asAtViewAssembler;

    public AsAtCheckService(AsAtViewAssembler asAtViewAssembler) {
        this.asAtViewAssembler = asAtViewAssembler;
    }

    public AsAtDecisionResponseDto check(String accountExternalId, String capabilityKey, LocalDate asOf) {
        var assembled = asAtViewAssembler.assemble(accountExternalId, capabilityKey, asOf);

        // The evaluation instant is the end of the day asked about, not now: the trace stamps what
        // it resolved, and stamping "now" on an answer about March would be a small lie in the one
        // artifact the operator is meant to be able to trust literally.
        var evaluatedAt = asOf.plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        var explanation = Resolver.explain(assembled.view(), accountExternalId,
            assembled.capability().key(), evaluatedAt);

        return new AsAtDecisionResponseDto(
            asOf.toString(),
            DecisionMapper.toResponse(explanation, assembled.capability()),
            assembled.capabilityRetiredSince().map(Timestamps::iso).orElse(null));
    }
}
