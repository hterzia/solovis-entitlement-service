package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.service.admin.dto.CapabilityCreateRequest;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.store.SnapshotVersionRepository;
import com.solovis.entitlement.service.time.Timestamps;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * data-model.md, snapshot_version: "rows older than a configured horizon (7 days) may be pruned,
 * after which a lagging replica must full-resync — pruning versions is safe precisely because they
 * are a transport concern, whereas pruning audit events is forbidden."
 *
 * <p>Until this existed, {@code snapshot_version} was the one table that grew without bound while
 * being explicitly designed to be prunable, and {@link DeltaFeedService}'s 410 branch — the whole
 * full-resync path in the SDK — was unreachable against the real service.
 *
 * <p>Fixtures publish <em>real</em> changes and then backdate the resulting rows, rather than
 * inserting version rows by hand. {@code SnapshotPublisher} derives the next version from the
 * in-memory snapshot while the table uses its own autoincrement, so hand-inserted rows desynchronise
 * the two counters into a state production can never reach — and a test that reaches it is testing
 * fiction. Backdating {@code published_at} is the only thing being faked here: the passage of time.
 *
 * <p>Deliberately not {@code @Transactional}: the pruner and the feed route read committed rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SnapshotVersionPrunerTest {

    @Autowired MockMvc mockMvc;
    @Autowired SnapshotVersionPruner pruner;
    @Autowired SnapshotVersionRepository snapshotVersionRepository;
    @Autowired CapabilityAdminService capabilityAdminService;

    @Autowired
    @Qualifier("entitlementWriteJdbcClient")
    JdbcClient jdbcClient;

    @Test
    void prunesRowsPastTheHorizonAndReportsHowManyItRemoved() {
        long stale1 = publishChange("tprune.removed.one");
        long stale2 = publishChange("tprune.removed.two");
        long fresh = publishChange("tprune.removed.three");
        backdate(stale1, 30);
        backdate(stale2, 8);
        backdate(fresh, 1);
        publishChange("tprune.removed.current");

        int removed = pruner.prune();

        assertThat(removed).isGreaterThanOrEqualTo(2);
        assertThat(snapshotVersionRepository.findByVersion(stale1)).isEmpty();
        assertThat(snapshotVersionRepository.findByVersion(stale2)).isEmpty();
        assertThat(snapshotVersionRepository.findByVersion(fresh))
            .as("one day old is well inside a seven day horizon")
            .isPresent();
    }

    @Test
    void neverPrunesTheCurrentVersionEvenWhenItIsOlderThanTheHorizon() {
        // A quiet estate — no writes for months — must not lose the row /v1/snapshot/version and
        // /full read publishedAt from, or every poll would report a different publish time for a
        // version that never changed.
        long current = publishChange("tprune.quiet.probe");
        backdate(current, 90);

        pruner.prune();

        assertThat(snapshotVersionRepository.findByVersion(current))
            .as("the version being served is load-bearing for the feed, whatever its age")
            .isPresent();
    }

    @Test
    void aReplicaAskingForAPrunedVersionIsToldToFullResync() throws Exception {
        long stale = publishChange("tprune.resync.probe");
        backdate(stale, 30);
        publishChange("tprune.resync.current");

        pruner.prune();
        assertThat(snapshotVersionRepository.findByVersion(stale)).isEmpty();

        // snapshot-feed.md: "since older than the retained horizon (7 days) -> 410
        // entitlement/snapshot-too-old -> discard and GET /v1/snapshot/full". The replica asks for
        // the version just before the pruned one, so the delta it needs starts at a row that is gone.
        mockMvc.perform(get("/v1/snapshot").param("since", String.valueOf(stale - 1)))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.type").value("entitlement/snapshot-too-old"));
    }

    @Test
    void horizonDefaultsToTheSevenDaysTheContractStates() {
        assertThat(pruner.retention()).isEqualTo(Duration.ofDays(7));
    }

    /** A genuine committed change; returns the version it published. */
    private long publishChange(String capabilityKey) {
        capabilityAdminService.create(new CapabilityCreateRequest(capabilityKey, "Prune probe", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));
        return snapshotVersionRepository.findLatest().orElseThrow().version();
    }

    /** The one thing this test fakes: how long ago a real version was published. */
    private void backdate(long version, int days) {
        jdbcClient.sql("UPDATE snapshot_version SET published_at = :publishedAt WHERE version = :version")
            .param("publishedAt", Timestamps.iso(Instant.now().minus(Duration.ofDays(days))))
            .param("version", version)
            .update();
    }
}
