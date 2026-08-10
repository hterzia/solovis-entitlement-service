package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.service.admin.dto.CapabilityCreateRequest;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.store.ServiceStateRepository;
import com.solovis.entitlement.service.time.Timestamps;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * snapshot-feed.md lists {@code conformance.changed} among the delta change kinds: "replacement
 * vector set / re-run the gate before serving".
 *
 * <p>It exists because a delta-derived replica inherits its predecessor's vectors — only a full
 * resync fetches a new set. So a replica running throughout a service redeploy would otherwise keep
 * validating against the vectors it started with, which is exactly the case a newly added vector was
 * meant to catch.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConformanceAnnouncerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ConformanceAnnouncer announcer;
    @Autowired ConformanceVectorSet vectorSet;
    @Autowired ServiceStateRepository serviceStateRepository;
    @Autowired SnapshotAssembler snapshotAssembler;
    @Autowired com.solovis.entitlement.service.store.SnapshotVersionRepository snapshotVersionRepository;
    @Autowired CapabilityAdminService capabilityAdminService;
    @Autowired Clock clock;

    private long currentVersion() {
        return snapshotVersionRepository.findLatest().map(row -> row.version()).orElse(0L);
    }

    @Test
    void announcesNothingWhenTheVectorSetIsUnchanged() {
        // Startup already recorded this build's digest, so a second pass has nothing to say.
        assertThat(announcer.announceIfChanged()).isEmpty();
        assertThat(announcer.announceIfChanged()).isEmpty();
    }

    @Test
    void startupRecordsTheDigestOfTheVectorSetItShipped() {
        assertThat(serviceStateRepository.find(ServiceStateRepository.CONFORMANCE_DIGEST))
            .contains(vectorSet.digest());
    }

    @Test
    void aChangedVectorSetIsAnnouncedAsADeltaCarryingTheWholeReplacementSet() throws Exception {
        // At least one audit event must exist for a version row to reference (data-model.md: a
        // snapshot_version names "the moment it captures").
        capabilityAdminService.create(new CapabilityCreateRequest("tconf.announce.probe", "Announce probe", null,
            "SWITCH", new ValueDto("SWITCH", false, null, null, null, null), null, null));
        long before = currentVersion();
        pretendADifferentBuildWasAnnounced();

        var published = announcer.announceIfChanged();

        assertThat(published).contains(before + 1);
        mockMvc.perform(get("/v1/snapshot").param("since", String.valueOf(before)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.changes[0].kind").value("conformance.changed"))
            .andExpect(jsonPath("$.changes[0].vectors.length()")
                .value(org.hamcrest.Matchers.greaterThanOrEqualTo(40)))
            .andExpect(jsonPath("$.changes[0].vectors[0].kind").value("conformance"))
            .andExpect(jsonPath("$.changes[0].vectors[0].model.capabilities").isArray())
            .andExpect(jsonPath("$.changes[0].vectors[0].expect.allowed").exists());

        assertThat(serviceStateRepository.find(ServiceStateRepository.CONFORMANCE_DIGEST))
            .as("the announcement is recorded, so a restart does not repeat it")
            .contains(vectorSet.digest());
    }

    @Test
    void anAnnouncementChangesNoDecisionBecauseItTouchesOnlyTheVersion() throws Exception {
        capabilityAdminService.create(new CapabilityCreateRequest("tconf.inert.probe", "Inert probe", null,
            "SWITCH", new ValueDto("SWITCH", false, null, null, null, null), null, null));
        var before = snapshotAssembler.assembleFull();
        pretendADifferentBuildWasAnnounced();

        announcer.announceIfChanged();
        var after = snapshotAssembler.assembleFull();

        assertThat(after.snapshotVersion()).isGreaterThan(before.snapshotVersion());
        assertThat(after.capabilities()).hasSameSizeAs(before.capabilities());
        assertThat(after.plans()).hasSameSizeAs(before.plans());
        assertThat(after.accountAssignments()).hasSameSizeAs(before.accountAssignments());
        assertThat(after.allLiveOverrides()).hasSameSizeAs(before.allLiveOverrides());
    }

    @Test
    void theDigestTracksTheWirePayloadRatherThanTheJavaObjects() {
        assertThat(vectorSet.digest())
            .as("stable across calls, so an unchanged build never announces")
            .isEqualTo(vectorSet.digest());
        assertThat(vectorSet.projected()).hasSameSizeAs(vectorSet.projected());
    }

    /** Rewinds the recorded digest so the next pass sees this build's set as new. */
    private void pretendADifferentBuildWasAnnounced() {
        serviceStateRepository.put(ServiceStateRepository.CONFORMANCE_DIGEST, "an-earlier-builds-digest",
            Timestamps.iso(clock.instant()));
    }
}
