package com.solovis.entitlement.service.api.dto;

public record SnapshotVersionResponseDto(long version, String publishedAt, int format, int resolverContract) {}
