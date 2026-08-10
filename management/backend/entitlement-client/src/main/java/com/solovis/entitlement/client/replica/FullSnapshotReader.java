package com.solovis.entitlement.client.replica;

import com.solovis.entitlement.client.wire.ClientJson;
import com.solovis.entitlement.client.wire.FeedDtos;
import com.solovis.entitlement.client.wire.ValueDto;
import com.solovis.entitlement.client.wire.WireMapper;
import com.solovis.entitlement.core.conformance.ConformanceVector;
import com.solovis.entitlement.core.model.AccountOverride;
import com.solovis.entitlement.core.model.CapabilityKey;
import com.solovis.entitlement.core.model.PlanEntitlement;
import com.solovis.entitlement.core.view.SnapshotBuilder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.logging.Logger;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;

/**
 * Turns the NDJSON body of {@code GET /v1/snapshot/full} into a {@link Replica}.
 *
 * <p>A snapshot is applied whole or not at all. A body without a matching footer is a truncated
 * response, and a truncated response that were partially applied would be a wrong answer with
 * nothing to diagnose it by — so it is discarded.
 */
public final class FullSnapshotReader {

    private static final Logger LOG = Logger.getLogger(FullSnapshotReader.class.getName());

    private FullSnapshotReader() {}

    /** A feed body that cannot be trusted as a complete, single-version snapshot. */
    public static final class MalformedFeedException extends RuntimeException {
        public MalformedFeedException(String message) {
            super(message);
        }
        public MalformedFeedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static Replica read(InputStream ndjson) {
        List<JsonNode> nodes;
        try (var reader = new BufferedReader(new InputStreamReader(ndjson, StandardCharsets.UTF_8))) {
            nodes = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                nodes.add(ClientJson.MAPPER.readTree(line));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (JacksonException e) {
            throw new MalformedFeedException("Could not parse a line of the snapshot feed.", e);
        }

        if (nodes.isEmpty() || !"header".equals(kindOf(nodes.get(0)))) {
            throw new MalformedFeedException(
                "A snapshot feed must start with a header line; the body was discarded.");
        }

        // Tracks what was being applied when a failure hit, so the wrapped exception below can
        // name it — cheap to maintain, and it is the difference between "line 4 (kind
        // 'capability') could not be applied" and an opaque stack trace.
        String currentKind = "header";
        int currentLine = 1;
        try {
            var header = ClientJson.MAPPER.treeToValue(nodes.get(0), FeedDtos.HeaderLine.class);
            var publishedAt = Instant.parse(header.publishedAt());

            var builder = new SnapshotBuilder();
            var overridesByRef = new HashMap<Long, AccountOverride>();
            var vectors = new ArrayList<ConformanceVector>();
            FeedDtos.FooterLine footer = null;

            for (int i = 1; i < nodes.size(); i++) {
                if (footer != null) {
                    throw new MalformedFeedException(
                        "A line was found after the footer; the feed must end at the footer.");
                }
                var node = nodes.get(i);
                currentLine = i + 1;
                currentKind = kindOf(node);
                switch (currentKind) {
                    case "footer" -> footer = ClientJson.MAPPER.treeToValue(node, FeedDtos.FooterLine.class);
                    case "capability" -> builder.capability(WireMapper.toCapability(
                        ClientJson.MAPPER.treeToValue(node, FeedDtos.CapabilityLine.class), publishedAt));
                    case "plan" -> applyPlan(builder, ClientJson.MAPPER.treeToValue(node, FeedDtos.PlanLine.class));
                    case "account" -> builder.account(WireMapper.toAccount(
                        ClientJson.MAPPER.treeToValue(node, FeedDtos.AccountLine.class)));
                    case "override" -> {
                        var override = WireMapper.toOverride(
                            ClientJson.MAPPER.treeToValue(node, FeedDtos.OverrideLine.class));
                        builder.override(override);
                        overridesByRef.put(override.id().getAsLong(), override);
                    }
                    case "conformance" -> vectors.add(toVector(
                        ClientJson.MAPPER.treeToValue(node, FeedDtos.ConformanceLine.class), publishedAt));
                    default -> LOG.warning("Skipping unknown snapshot feed line kind '" + currentKind + "'.");
                }
            }

            if (footer == null) {
                throw new MalformedFeedException(
                    "Snapshot feed has no footer; a body without one may be truncated and was discarded.");
            }
            if (footer.version() != header.version()) {
                throw new MalformedFeedException("Footer version " + footer.version()
                    + " does not match header version " + header.version()
                    + "; the feed is truncated or corrupt and was discarded.");
            }

            return new Replica(builder.build(header.version()), overridesByRef, publishedAt, vectors,
                header.format(), header.resolverContract());
        } catch (MalformedFeedException e) {
            throw e;
        } catch (RuntimeException e) {
            // Anything else escaping the parse/dispatch path — a JacksonException, a core model
            // constructor rejecting an invalid value (e.g. a SWITCH capability declaring an
            // off-value), WireMapper.refToId rejecting a malformed ref, a conformance line missing
            // a model key — means this feed cannot be trusted. It must not surface as anything
            // other than MalformedFeedException, or a caller's catch (MalformedFeedException)
            // stops meaning "this feed is bad".
            throw new MalformedFeedException(
                "Line " + currentLine + " (kind '" + currentKind + "') could not be applied: " + e.getMessage(), e);
        }
    }

    private static void applyPlan(SnapshotBuilder builder, FeedDtos.PlanLine line) {
        builder.plan(WireMapper.toPlan(line));
        if (line.entitlements() != null) {
            line.entitlements().forEach((capabilityKey, valueDto) -> builder.planEntitlement(
                new PlanEntitlement(line.key(), new CapabilityKey(capabilityKey), WireMapper.toValue(valueDto))));
        }
    }

    private static String kindOf(JsonNode node) {
        var kind = node.get("kind");
        return kind == null ? null : kind.asString();
    }

    /**
     * Builds one {@link ConformanceVector} from a self-contained {@code conformance} line. The
     * vector's {@code model} carries its own miniature snapshot, so it is assembled through the
     * same {@link SnapshotBuilder} the real feed uses — the gate must exercise the real path.
     */
    private static ConformanceVector toVector(FeedDtos.ConformanceLine line, Instant publishedAt) {
        var model = line.model();
        var builder = new SnapshotBuilder();

        for (JsonNode capabilityNode : model.get("capabilities")) {
            builder.capability(WireMapper.toCapability(
                ClientJson.MAPPER.treeToValue(capabilityNode, FeedDtos.CapabilityLine.class), publishedAt));
        }
        for (JsonNode planNode : model.get("plans")) {
            applyPlan(builder, ClientJson.MAPPER.treeToValue(planNode, FeedDtos.PlanLine.class));
        }
        for (JsonNode accountNode : model.get("accounts")) {
            builder.account(WireMapper.toAccount(
                ClientJson.MAPPER.treeToValue(accountNode, FeedDtos.AccountLine.class)));
        }
        for (JsonNode overrideNode : model.get("overrides")) {
            builder.override(WireMapper.toOverride(
                ClientJson.MAPPER.treeToValue(overrideNode, FeedDtos.OverrideLine.class)));
        }

        var expect = line.expect();
        var expectedValue = WireMapper.toValue(
            ClientJson.MAPPER.treeToValue(expect.get("value"), ValueDto.class));

        return new ConformanceVector(
            line.id(),
            builder.build(0L),
            model.get("account").asString(),
            new CapabilityKey(model.get("capability").asString()),
            expect.get("allowed").asBoolean(),
            expectedValue);
    }
}
