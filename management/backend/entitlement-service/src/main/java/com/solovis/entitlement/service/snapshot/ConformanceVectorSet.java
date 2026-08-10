package com.solovis.entitlement.service.snapshot;

import com.solovis.entitlement.core.conformance.ConformanceVector;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * The conformance vector set as it goes onto the wire, plus a fingerprint of it.
 *
 * <p>The vectors are compiled into {@code entitlement-core}, so they change only when this service
 * is redeployed. That is precisely the case {@code conformance.changed} exists for: a replica that
 * has been running throughout the redeploy holds the <em>old</em> vectors, because a delta-derived
 * candidate inherits its predecessor's set and only a full resync fetches a new one. Without an
 * announcement it would keep serving, never having re-run its gate against the tighter set — which
 * is exactly the situation where a newly-added vector would have caught it.
 *
 * <p>The fingerprint is taken over the <em>serialised</em> projection rather than over the Java
 * objects, so it changes if and only if what a replica receives changes. A refactor that leaves the
 * wire payload identical must not trigger an announcement, and a change to how a vector is projected
 * must.
 */
@Component
public class ConformanceVectorSet {

    private final ObjectMapper mapper;

    public ConformanceVectorSet(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Every vector this build carries, in the shape the feed emits them. */
    public List<ConformanceVectorDto> projected() {
        return ConformanceVector.spec5WorkedExamples().stream()
            .map(FullSnapshotWriter::conformanceLine)
            .toList();
    }

    /** A stable fingerprint of {@link #projected()} — equal payloads give equal digests. */
    public String digest() {
        String json = mapper.writeValueAsString(projected());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
