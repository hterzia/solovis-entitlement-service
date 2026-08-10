package com.solovis.entitlement.client.wire;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/**
 * The NDJSON line shapes of {@code GET /v1/snapshot/full}, one record per {@code kind}.
 *
 * <p>These mirror what {@code FullSnapshotWriter} actually emits, which is not identical to the
 * example block in {@code snapshot-feed.md}: the override line's GRANT/HOLD kind lives on
 * {@code overrideKind} (the doc shows {@code kind}), and absent {@code offValue}/{@code tiers}
 * are omitted keys rather than nulls.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class FeedDtos {

    private FeedDtos() {}

    public record Counts(long capabilities, long plans, long accounts, long overrides) {}

    public record HeaderLine(
        String kind, long version, int format, int resolverContract, String publishedAt, Counts counts) {}

    public record TierDto(String tier, int ordinal, String displayName) {}

    public record CapabilityLine(
        String kind,
        String key,
        String area,
        String valueType,
        @JsonProperty("default") ValueDto defaultValue,
        ValueDto offValue,
        List<TierDto> tiers,
        String status) {}

    public record PlanLine(
        String kind,
        String key,
        String status,
        boolean isDefaultForNewAccounts,
        Map<String, ValueDto> entitlements) {}

    public record AccountLine(String kind, String external, String planKey) {}

    public record OverrideLine(
        String kind, String ref, String account, String capability, String overrideKind, ValueDto value) {}

    /**
     * A self-contained conformance vector. {@code model} is a nested object carrying its own
     * miniature snapshot, so it is held as a tree and unpacked by {@code ConformanceGate} rather
     * than being bound to a fixed record here.
     */
    public record ConformanceLine(String kind, String id, JsonNode model, JsonNode expect) {}

    public record FooterLine(String kind, long version, long recordCount) {}
}
