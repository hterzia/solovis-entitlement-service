package com.solovis.entitlement.service.admin.dto;

import java.util.List;

public record AccountSearchResponseDto(List<AccountSummaryDto> accounts, String nextCursor) {}
