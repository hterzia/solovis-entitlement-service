package com.solovis.entitlement.service.snapshot;

/** Wire shape for one `{"kind":"conformance", ...}` NDJSON line — self-contained per snapshot-feed.md so a replica can evaluate it without the real data. */
public record ConformanceVectorDto(String kind, String id, Object model, Object expect) {}
