package com.solovis.entitlement.service.admin.dto;

import com.solovis.entitlement.service.api.dto.DecisionResponseDto;
import com.solovis.entitlement.service.dto.ValueDto;
import java.util.List;

public record PlanPreviewResponseDto(
    String planKey, long affectedAccountCount, List<Diff> diff, PreviewAccount previewAccount, String previewToken
) {
    public record Diff(String capability, ValueDto before, ValueDto after, String note) {}
    public record PreviewAccount(String account, List<Effect> effects) {}
    public record Effect(String capability, DecisionResponseDto before, DecisionResponseDto after, boolean changed, String note) {}
}
