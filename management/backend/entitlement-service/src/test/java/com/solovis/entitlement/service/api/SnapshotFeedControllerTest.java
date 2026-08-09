package com.solovis.entitlement.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solovis.entitlement.core.conformance.ResolverContract;
import com.solovis.entitlement.service.snapshot.SnapshotHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void versionReflectsTheHeldSnapshotAndDisablesCaching() throws Exception {
        long current = snapshotHolder.current().snapshotVersion();

        mockMvc.perform(get("/v1/snapshot/version"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.version").value(current))
            .andExpect(jsonPath("$.format").value(1))
            .andExpect(jsonPath("$.resolverContract").value(ResolverContract.VERSION));
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
    void deltaSinceAheadOfCurrentVersionIsRejectedAsValidationFailed() throws Exception {
        long current = snapshotHolder.current().snapshotVersion();

        mockMvc.perform(get("/v1/snapshot").param("since", String.valueOf(current + 1000)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.type").value("entitlement/validation-failed"));
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
