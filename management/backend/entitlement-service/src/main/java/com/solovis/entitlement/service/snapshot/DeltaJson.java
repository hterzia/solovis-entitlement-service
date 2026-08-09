package com.solovis.entitlement.service.snapshot;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** (De)serialises one {@link DeltaChange} to/from the {@code snapshot_version.delta_json} column. A dedicated, minimal mapper — this format is internal, decoupled from the API-response Jackson configuration in Task 10. */
public final class DeltaJson {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
        .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
        .build();

    private DeltaJson() {}

    public static String write(DeltaChange change) {
        try {
            return MAPPER.writeValueAsString(change);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialise delta change: " + change, e);
        }
    }

    public static DeltaChange read(String json) {
        try {
            return MAPPER.readValue(json, DeltaChange.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialise delta_json: " + json, e);
        }
    }
}
