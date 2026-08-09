package com.solovis.entitlement.client.wire;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ClientJsonTest {

    @Test
    void unknownWirePropertiesAreIgnoredSoAServiceCanAddFieldsWithoutBreakingEveryDeployedReplica() {
        var json = """
            {"version":48211,"publishedAt":"2026-08-09T14:03:10.900Z","format":1,
             "resolverContract":1,"somethingAddedNextYear":"ignored"}""";

        var node = ClientJson.MAPPER.readTree(json);

        assertThat(node.get("version").asLong()).isEqualTo(48211L);
    }

    @Test
    void nullValuedPropertiesAreOmittedOnWriteMatchingTheServicesNonNullInclusion() {
        record Sample(String present, String absent) {}

        var json = ClientJson.MAPPER.writeValueAsString(new Sample("here", null));

        assertThat(json).isEqualTo("{\"present\":\"here\"}");
    }

    @Test
    void mapContentNullsAreOmittedTooBecauseTheFeedOmitsOffValueAndTiersRatherThanNullingThem() {
        var map = new java.util.LinkedHashMap<String, Object>();
        map.put("kind", "capability");
        map.put("offValue", null);

        var json = ClientJson.MAPPER.writeValueAsString(map);

        assertThat(json).isEqualTo("{\"kind\":\"capability\"}");
    }

    @Test
    void instantsAreWrittenAsIsoTextNotEpochNumbersSoTheDiskCacheMatchesTheFeed() {
        record Stamped(java.time.Instant at) {}

        var json = ClientJson.MAPPER.writeValueAsString(
            new Stamped(java.time.Instant.parse("2026-08-09T14:03:10.900Z")));

        assertThat(json).contains("2026-08-09T14:03:10.900Z");
    }
}
