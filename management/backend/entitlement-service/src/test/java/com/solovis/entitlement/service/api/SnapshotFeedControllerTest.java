package com.solovis.entitlement.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solovis.entitlement.core.conformance.ResolverContract;
import com.solovis.entitlement.service.admin.dto.AccountCreateRequest;
import com.solovis.entitlement.service.admin.dto.CapabilityCreateRequest;
import com.solovis.entitlement.service.admin.dto.OverrideCreateRequest;
import com.solovis.entitlement.service.admin.dto.PlanCreateRequest;
import com.solovis.entitlement.service.admin.service.AccountAdminService;
import com.solovis.entitlement.service.admin.service.CapabilityAdminService;
import com.solovis.entitlement.service.admin.service.OverrideAdminService;
import com.solovis.entitlement.service.admin.service.PlanAdminService;
import com.solovis.entitlement.service.dto.ValueDto;
import com.solovis.entitlement.service.snapshot.SnapshotHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;

@SpringBootTest
@AutoConfigureMockMvc
class SnapshotFeedControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired SnapshotHolder snapshotHolder;
    @Autowired CapabilityAdminService capabilityAdminService;
    @Autowired PlanAdminService planAdminService;
    @Autowired AccountAdminService accountAdminService;
    @Autowired OverrideAdminService overrideAdminService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MILLIS_ISO_8601_PATTERN = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z";

    @Test
    void versionReflectsTheHeldSnapshotAndDisablesCaching() throws Exception {
        long current = snapshotHolder.current().snapshotVersion();

        mockMvc.perform(get("/v1/snapshot/version"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.version").value(current))
            .andExpect(jsonPath("$.format").value(1))
            .andExpect(jsonPath("$.resolverContract").value(ResolverContract.VERSION))
            .andExpect(jsonPath("$.publishedAt", org.hamcrest.Matchers.matchesPattern(MILLIS_ISO_8601_PATTERN)));
    }

    @Test
    void versionsPublishedAtIsStableAcrossRepeatedPollsOfAnUnchangedVersion() throws Exception {
        // A real snapshot_version row must exist for the polled version — publish one first so the
        // controller reads a stored publishedAt rather than falling back to a fresh clock read (which
        // only happens pre-first-publish, and is the one case that's inherently not pollable-stable).
        capabilityAdminService.create(new CapabilityCreateRequest("t9c.stability-probe.count", "Stability probe", null, "QUANTITY",
            new ValueDto("QUANTITY", null, 0L, null, null, null), null, null));

        String first = mockMvc.perform(get("/v1/snapshot/version")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(get("/v1/snapshot/version")).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat((String) com.jayway.jsonpath.JsonPath.read(first, "$.publishedAt"))
            .isEqualTo(com.jayway.jsonpath.JsonPath.read(second, "$.publishedAt"));
    }

    @Test
    void fullSnapshotHeaderPublishedAtMatchesTheVersionEndpointForTheSameVersion() throws Exception {
        // A real snapshot_version row must exist so both endpoints resolve the same stored
        // publishedAt rather than either one falling back to a fresh clock read.
        capabilityAdminService.create(new CapabilityCreateRequest("t9c.publishedat-match.probe", "PublishedAt match probe", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));

        String versionResponse = mockMvc.perform(get("/v1/snapshot/version"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String versionPublishedAt = com.jayway.jsonpath.JsonPath.read(versionResponse, "$.publishedAt");

        MvcResult started = mockMvc.perform(get("/v1/snapshot/full"))
            .andExpect(request().asyncStarted())
            .andReturn();
        MvcResult finished = mockMvc.perform(asyncDispatch(started))
            .andExpect(status().isOk())
            .andReturn();

        List<JsonNode> lines = readGzippedNdjsonLines(finished.getResponse().getContentAsByteArray());
        JsonNode header = lines.get(0);
        assertThat(header.get("kind").asText()).isEqualTo("header");
        assertThat(header.get("publishedAt").asText()).isEqualTo(versionPublishedAt);
    }

    @Test
    void fullSnapshotIsGzippedNdjsonWithHeaderFirstAndFooterLast() throws Exception {
        MvcResult started = mockMvc.perform(get("/v1/snapshot/full"))
            .andExpect(request().asyncStarted())
            .andReturn();

        MvcResult finished = mockMvc.perform(asyncDispatch(started))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Encoding", "gzip"))
            .andExpect(content().contentTypeCompatibleWith("application/x-ndjson"))
            .andReturn();

        List<JsonNode> lines = readGzippedNdjsonLines(finished.getResponse().getContentAsByteArray());
        assertThat(lines).isNotEmpty();

        JsonNode header = lines.get(0);
        JsonNode footer = lines.get(lines.size() - 1);
        assertThat(header.get("kind").asText()).isEqualTo("header");
        assertThat(footer.get("kind").asText()).isEqualTo("footer");
        long current = snapshotHolder.current().snapshotVersion();
        assertThat(header.get("version").asLong()).isEqualTo(current);
        assertThat(footer.get("version").asLong()).isEqualTo(current);
        assertThat(footer.get("recordCount").asInt()).isEqualTo(lines.size());

        for (JsonNode line : lines.subList(1, lines.size() - 1)) {
            assertThat(line.get("kind").asText())
                .isIn("capability", "plan", "account", "override", "conformance");
        }

        long overrideLinesWithOverrideKind = lines.stream()
            .filter(l -> "override".equals(l.path("kind").asText()))
            .filter(l -> l.has("overrideKind"))
            .count();
        long overrideLinesTotal = lines.stream().filter(l -> "override".equals(l.path("kind").asText())).count();
        assertThat(overrideLinesWithOverrideKind).isEqualTo(overrideLinesTotal);

        long conformanceLinesWithModel = lines.stream()
            .filter(l -> "conformance".equals(l.path("kind").asText()))
            .filter(l -> l.has("model") && !l.get("model").isNull())
            .count();
        long conformanceLinesTotal = lines.stream().filter(l -> "conformance".equals(l.path("kind").asText())).count();
        assertThat(conformanceLinesTotal).isGreaterThan(0);
        assertThat(conformanceLinesWithModel).isEqualTo(conformanceLinesTotal);
    }

    @Test
    void deltaSinceCurrentVersionReturnsEmptyChanges() throws Exception {
        long current = snapshotHolder.current().snapshotVersion();

        mockMvc.perform(get("/v1/snapshot").param("since", String.valueOf(current)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fromVersion").value(current))
            .andExpect(jsonPath("$.toVersion").value(current))
            .andExpect(jsonPath("$.changes").isArray())
            .andExpect(jsonPath("$.changes").isEmpty());
    }

    @Test
    void deltaChangesAreFlatObjectsMatchingTheContractNotNestedUnderAChangeKey() throws Exception {
        long before = snapshotHolder.current().snapshotVersion();
        capabilityAdminService.create(new CapabilityCreateRequest("t9c.delta-shape.probe", "Delta shape probe", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));

        String response = mockMvc.perform(get("/v1/snapshot").param("since", String.valueOf(before)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        JsonNode changes = MAPPER.readTree(response).get("changes");
        assertThat(changes).isNotEmpty();
        JsonNode change = changes.get(0);
        assertThat(change.has("version")).isTrue();
        assertThat(change.has("kind")).isTrue();
        assertThat(change.has("change")).isFalse();
    }

    @Test
    void deltaSinceAheadOfCurrentVersionIsRejectedAsValidationFailed() throws Exception {
        long current = snapshotHolder.current().snapshotVersion();

        mockMvc.perform(get("/v1/snapshot").param("since", String.valueOf(current + 1000)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.type").value("entitlement/validation-failed"));
    }

    // GAP 1 — snapshot-feed.md's opening note: override records travel as (account, capability, kind,
    // value) only. reason/createdBy/createdAt are commercially sensitive (an internal reason like
    // "suspended pending investigation" must never reach a consuming service) and must never appear on
    // either feed surface. This pins that guarantee with a real override carrying a distinctive reason.
    @Test
    void fullSnapshotOverrideLineCarriesNoReasonCreatedByOrCreatedAtAndTheReasonTextNeverAppears() throws Exception {
        String reason = "t30 confidential investigation reason";
        planAdminService.create(new PlanCreateRequest("t30-full-plan", "T30 full plan", null));
        planAdminService.designateDefault("t30-full-plan");
        capabilityAdminService.create(new CapabilityCreateRequest("t30.full.privacy-probe", "T30 full privacy probe", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));
        accountAdminService.create(new AccountCreateRequest("t30-acct-full-privacy", null));
        var created = overrideAdminService.create("t30-acct-full-privacy", new OverrideCreateRequest("t30.full.privacy-probe", "GRANT",
            new ValueDto("SWITCH", true, null, null, null, null), reason));

        MvcResult started = mockMvc.perform(get("/v1/snapshot/full"))
            .andExpect(request().asyncStarted())
            .andReturn();
        MvcResult finished = mockMvc.perform(asyncDispatch(started))
            .andExpect(status().isOk())
            .andReturn();

        byte[] gzipped = finished.getResponse().getContentAsByteArray();
        var out = new ByteArrayOutputStream();
        try (var gzipIn = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            gzipIn.transferTo(out);
        }
        String body = out.toString(StandardCharsets.UTF_8);

        // The confidential reason text itself must not appear anywhere in the full gunzipped body —
        // a stronger, simpler check than looking only for the "reason" key.
        assertThat(body).doesNotContain(reason);

        String overrideRawLine = Arrays.stream(body.split("\n"))
            .filter(line -> line.contains("\"kind\":\"override\""))
            .filter(line -> line.contains("\"ref\":\"" + created.overrideId() + "\""))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No override line for " + created.overrideId() + " found in full snapshot body"));

        assertThat(overrideRawLine).doesNotContain("\"reason\"");
        assertThat(overrideRawLine).doesNotContain("\"createdBy\"");
        assertThat(overrideRawLine).doesNotContain("\"createdAt\"");
        assertThat(overrideRawLine).contains("\"ref\"");
        assertThat(overrideRawLine).contains("\"account\"");
        assertThat(overrideRawLine).contains("\"capability\"");
        assertThat(overrideRawLine).contains("\"overrideKind\"");
        assertThat(overrideRawLine).contains("\"value\"");
    }

    @Test
    void deltaOverrideCreatedChangeCarriesNoReasonCreatedByOrCreatedAtAndTheReasonTextNeverAppears() throws Exception {
        String reason = "t30 confidential investigation reason";
        long before = snapshotHolder.current().snapshotVersion();

        planAdminService.create(new PlanCreateRequest("t30-delta-plan", "T30 delta plan", null));
        planAdminService.designateDefault("t30-delta-plan");
        capabilityAdminService.create(new CapabilityCreateRequest("t30.delta.privacy-probe", "T30 delta privacy probe", null, "SWITCH",
            new ValueDto("SWITCH", false, null, null, null, null), null, null));
        accountAdminService.create(new AccountCreateRequest("t30-acct-delta-privacy", null));
        var created = overrideAdminService.create("t30-acct-delta-privacy", new OverrideCreateRequest("t30.delta.privacy-probe", "GRANT",
            new ValueDto("SWITCH", true, null, null, null, null), reason));

        String response = mockMvc.perform(get("/v1/snapshot").param("since", String.valueOf(before)))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(reason);

        JsonNode changes = MAPPER.readTree(response).get("changes");
        JsonNode overrideCreated = null;
        for (JsonNode change : changes) {
            if ("override.created".equals(change.path("kind").asText())
                && created.overrideId().equals(change.path("ref").asText())) {
                overrideCreated = change;
                break;
            }
        }
        assertThat(overrideCreated).as("override.created change for %s", created.overrideId()).isNotNull();
        assertThat(overrideCreated.has("reason")).isFalse();
        assertThat(overrideCreated.has("createdBy")).isFalse();
        assertThat(overrideCreated.has("createdAt")).isFalse();
        assertThat(overrideCreated.has("ref")).isTrue();
        assertThat(overrideCreated.has("account")).isTrue();
        assertThat(overrideCreated.has("capability")).isTrue();
        assertThat(overrideCreated.has("overrideKind")).isTrue();
        assertThat(overrideCreated.has("value")).isTrue();
    }

    private static List<JsonNode> readGzippedNdjsonLines(byte[] gzipped) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var gzipIn = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
            gzipIn.transferTo(out);
        }
        var lines = new ArrayList<JsonNode>();
        for (String rawLine : out.toString(java.nio.charset.StandardCharsets.UTF_8).split("\n")) {
            if (!rawLine.isBlank()) {
                lines.add(MAPPER.readTree(rawLine));
            }
        }
        return lines;
    }
}
