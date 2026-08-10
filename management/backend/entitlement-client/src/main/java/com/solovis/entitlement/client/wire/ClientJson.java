package com.solovis.entitlement.client.wire;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The single JSON mapper for every wire surface this SDK touches, configured to match the
 * service's {@code JacksonConfig} exactly so a round-trip through the disk cache produces the
 * same bytes the feed produced.
 *
 * <p>Unknown properties are ignored on purpose: a replica running an older SDK must keep syncing
 * when the service starts emitting a field it has never heard of. Unknown <em>record kinds</em>
 * are a different matter and are handled per-surface — see {@code FullSnapshotReader} (skip) and
 * {@code DeltaApplier} (stop syncing).
 */
public final class ClientJson {

    public static final ObjectMapper MAPPER = JsonMapper.builder()
        .changeDefaultPropertyInclusion(incl -> incl
            .withValueInclusion(JsonInclude.Include.NON_NULL)
            .withContentInclusion(JsonInclude.Include.NON_NULL))
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS, DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    private ClientJson() {}
}
