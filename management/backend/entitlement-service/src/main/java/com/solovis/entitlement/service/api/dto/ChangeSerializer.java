package com.solovis.entitlement.service.api.dto;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/** Flattens {@link SnapshotDeltaResponseDto.Change} to {@code {"version":..., "kind":..., ...}} — see the record's javadoc for why this can't be a plain {@code @JsonUnwrapped}. */
final class ChangeSerializer extends ValueSerializer<SnapshotDeltaResponseDto.Change> {

    @Override
    public void serialize(SnapshotDeltaResponseDto.Change value, JsonGenerator gen, SerializationContext ctxt) throws JacksonException {
        gen.writeStartObject();
        gen.writeNumberProperty("version", value.version());
        JsonNode changeNode = ctxt.valueToTree(value.change());
        for (var field : changeNode.properties()) {
            gen.writeName(field.getKey());
            ctxt.writeTree(gen, field.getValue());
        }
        gen.writeEndObject();
    }
}
