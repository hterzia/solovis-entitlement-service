package com.solovis.entitlement.client.replica;

import static org.assertj.core.api.Assertions.assertThat;

import com.solovis.entitlement.core.conformance.ConformanceVector;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.EntitlementValue;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiskCacheTest {

    private static final String FEED = String.join("\n",
        """
        {"kind":"header","version":48211,"format":1,"resolverContract":1,\
        "publishedAt":"2026-08-09T14:03:10.900Z",\
        "counts":{"capabilities":2,"plans":1,"accounts":1,"overrides":1}}""",
        """
        {"kind":"capability","key":"support.level","area":"support","valueType":"TIER",\
        "default":{"type":"TIER","tier":"community","ordinal":0},\
        "tiers":[{"tier":"community","ordinal":0,"displayName":"Community"},\
        {"tier":"gold","ordinal":1,"displayName":"Gold"}],"status":"ACTIVE"}""",
        """
        {"kind":"capability","key":"legacy.export","area":"legacy","valueType":"SWITCH",\
        "default":{"type":"SWITCH","enabled":false},"status":"RETIRED"}""",
        """
        {"kind":"plan","key":"pro","status":"ACTIVE","isDefaultForNewAccounts":true,\
        "entitlements":{"support.level":{"type":"TIER","tier":"gold","ordinal":1}}}""",
        """
        {"kind":"account","external":"acct_9931","planKey":"pro"}""",
        """
        {"kind":"override","ref":"ovr_4471","account":"acct_9931","capability":"support.level",\
        "overrideKind":"HOLD","value":{"type":"TIER","tier":"community","ordinal":0}}""",
        """
        {"kind":"footer","version":48211,"recordCount":7}""");

    private static Replica sample() {
        return FullSnapshotReader.read(new ByteArrayInputStream(FEED.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aStoredReplicaReloadsIdenticallySoARestartDuringAnOutageKeepsTheCustomersEntitlements(
            @TempDir Path dir) {
        var original = sample();
        var cache = new DiskCache(dir);

        cache.store(original);
        var reloaded = cache.load().orElseThrow();

        assertThat(reloaded.version()).isEqualTo(original.version());
        assertThat(reloaded.publishedAt()).isEqualTo(original.publishedAt());
        assertThat(reloaded.format()).isEqualTo(original.format());
        assertThat(reloaded.resolverContract()).isEqualTo(original.resolverContract());
        assertThat(reloaded.snapshot().capabilities())
            .containsExactlyInAnyOrderElementsOf(original.snapshot().capabilities());
        assertThat(reloaded.snapshot().plans())
            .containsExactlyInAnyOrderElementsOf(original.snapshot().plans());
        assertThat(reloaded.snapshot().accountAssignments())
            .containsExactlyInAnyOrderElementsOf(original.snapshot().accountAssignments());
        assertThat(reloaded.snapshot().allLiveOverrides())
            .containsExactlyInAnyOrderElementsOf(original.snapshot().allLiveOverrides());
        assertThat(reloaded.overridesByRef()).isEqualTo(original.overridesByRef());
    }

    @Test
    void tierOrdersAndRetirementSurviveTheRoundTripBecauseTheyChangeWhatAnswersAreLegal(
            @TempDir Path dir) {
        var cache = new DiskCache(dir);
        cache.store(sample());

        var reloaded = cache.load().orElseThrow();
        var support = reloaded.snapshot().capability(new CapabilityKey("support.level")).orElseThrow();
        var legacy = reloaded.snapshot().capability(new CapabilityKey("legacy.export")).orElseThrow();

        assertThat(support.tierOrder().ordinalOf("gold")).hasValue(1);
        assertThat(legacy.isRetired()).isTrue();
    }

    @Test
    void planEntitlementsSurviveTheRoundTripBecauseTheyAreTheBaselineOfEveryDecision(@TempDir Path dir) {
        var cache = new DiskCache(dir);
        cache.store(sample());

        var reloaded = cache.load().orElseThrow();

        assertThat(reloaded.snapshot().planEntitlement("pro", new CapabilityKey("support.level")))
            .get().extracting(pe -> pe.value())
            .isEqualTo(new EntitlementValue.Tier("gold", 1));
    }

    @Test
    void conformanceVectorsAreNotCachedSoAReloadedReplicaCarriesNone(@TempDir Path dir) {
        var cache = new DiskCache(dir);
        cache.store(sample());

        assertThat(cache.load().orElseThrow().vectors()).isEmpty();
    }

    @Test
    void anEmptyDirectoryYieldsNoReplicaRatherThanAnError(@TempDir Path dir) {
        assertThat(new DiskCache(dir).load()).isEmpty();
    }

    @Test
    void aCorruptCacheFileYieldsNoReplicaRatherThanPropagatingAParseFailureIntoStartup(
            @TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("snapshot.ndjson"), "this is not ndjson\n");

        assertThat(new DiskCache(dir).load()).isEmpty();
    }

    @Test
    void storingTwiceLeavesExactlyOneCacheFileAndNoTemporaryBehind(@TempDir Path dir) throws Exception {
        var cache = new DiskCache(dir);
        cache.store(sample());
        cache.store(sample());

        try (var files = Files.list(dir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("snapshot.ndjson");
        }
    }

    @Test
    void storeCreatesTheCacheDirectoryWhenTheProductPointedAtOneThatDoesNotExistYet(@TempDir Path dir) {
        var nested = dir.resolve("var").resolve("cache").resolve("entitlements");

        new DiskCache(nested).store(sample());

        assertThat(new DiskCache(nested).load()).isPresent();
    }
}
