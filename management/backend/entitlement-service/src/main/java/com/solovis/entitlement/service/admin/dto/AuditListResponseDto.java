package com.solovis.entitlement.service.admin.dto;

import java.util.List;

public record AuditListResponseDto(List<AuditEventDto> events, String nextCursor) {}
