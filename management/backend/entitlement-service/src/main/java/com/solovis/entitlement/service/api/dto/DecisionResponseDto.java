package com.solovis.entitlement.service.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.solovis.entitlement.service.dto.ValueDto;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record DecisionResponseDto(
    String account, String capability, boolean allowed, ValueDto value,
    long snapshotVersion, String evaluatedAt, TraceDto trace
) {
    public record TraceDto(
        BaselineDto baseline, List<CandidateDto> grants, GrantStepDto grantStep,
        List<CandidateDto> holds, HoldStepDto holdStep, ResultDto result
    ) {
        public record BaselineDto(String source, String planKey, ValueDto value, String note) {}
        public record CandidateDto(String overrideId, ValueDto value, String reason, String createdBy, String createdAt, String outcome) {}
        public record GrantStepDto(boolean applied, String winner, ValueDto value, String note, String why) {}
        public record HoldStepDto(boolean applied, String winner, ValueDto value, String note, String why) {}
        public record ResultDto(ValueDto value, boolean allowed, String allowedReason) {}
    }
}
