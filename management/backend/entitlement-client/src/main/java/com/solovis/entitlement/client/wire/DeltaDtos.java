package com.solovis.entitlement.client.wire;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The envelope of {@code GET /v1/snapshot?since=N}.
 *
 * <p>Each element of {@code changes} is a flat object — {@code {"version":N,"kind":"…",…payload}}
 * — because the service's {@code ChangeSerializer} splices the payload's properties up alongside
 * {@code version} rather than nesting them. They stay as {@link JsonNode} so an unrecognised
 * {@code kind} can be reported precisely instead of failing the whole batch inside Jackson.
 */
public final class DeltaDtos {

    private DeltaDtos() {}

    public record DeltaResponse(
        int format, long fromVersion, long toVersion, String publishedAt, List<JsonNode> changes) {}
}
