package com.solovis.entitlement.client.transport;

import com.solovis.entitlement.client.replica.FullSnapshotReader;
import com.solovis.entitlement.client.replica.Replica;
import com.solovis.entitlement.client.wire.ClientJson;
import com.solovis.entitlement.client.wire.DecisionDtos;
import com.solovis.entitlement.client.wire.DeltaDtos;
import com.solovis.entitlement.client.wire.ProblemDto;
import com.solovis.entitlement.core.error.RetiredCapabilityException;
import com.solovis.entitlement.core.error.UnknownAccountException;
import com.solovis.entitlement.core.error.UnknownCapabilityException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.zip.GZIPInputStream;

/**
 * The only class in this SDK that opens a socket. It understands HTTP, gzip and the RFC 9457
 * problem model, and nothing about entitlements — {@code replica} never opens a socket, and this
 * class never resolves a decision. That seam is what lets the outage posture be tested without a
 * network.
 *
 * <p>Every non-2xx response becomes one of exactly two typed failures. A {@link
 * SnapshotTooOldException} means the delta path is unusable from the version the caller asked for
 * — a 410 with {@code type == "entitlement/snapshot-too-old"} (past the retained horizon) or a 422
 * on {@code ?since=} that carries {@code currentVersion} (the service was restored from a backup
 * and {@code since} is now ahead of it) — and the caller must fetch a full snapshot instead.
 * Everything else — connection failure, timeout, a 5xx, or a body this SDK could not parse —
 * becomes a {@link FeedUnavailableException}: always recoverable by backing off and retrying,
 * never a reason to change an answer.
 */
public final class FeedHttpClient implements AutoCloseable {

    private final URI baseUri;
    private final HttpClient http;
    private final Duration requestTimeout;
    private final boolean ownsHttpClient;

    public FeedHttpClient(URI baseUri, HttpClient http, Duration requestTimeout) {
        this(baseUri, http, requestTimeout, false);
    }

    /** Convenience constructor for a caller that does not already manage an {@link HttpClient}. */
    public FeedHttpClient(URI baseUri, Duration requestTimeout) {
        this(baseUri, HttpClient.newBuilder().connectTimeout(requestTimeout).build(), requestTimeout, true);
    }

    private FeedHttpClient(URI baseUri, HttpClient http, Duration requestTimeout, boolean ownsHttpClient) {
        this.baseUri = Objects.requireNonNull(baseUri, "baseUri");
        this.http = Objects.requireNonNull(http, "http");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.ownsHttpClient = ownsHttpClient;
    }

    public record SnapshotVersionDto(long version, String publishedAt, int format, int resolverContract) {}

    /** {@code GET /v1/snapshot/version} — deliberately trivial so it can be polled every 5 seconds. */
    public SnapshotVersionDto version() {
        var uri = resolve("/v1/snapshot/version");
        var response = send(uri, BodyHandlers.ofString());
        requireSuccess(uri, response.statusCode(), response.body());
        return parse(uri, response.body(), SnapshotVersionDto.class);
    }

    /** {@code GET /v1/snapshot/full} — gunzips the body and parses it into a whole-or-nothing {@link Replica}. */
    public Replica full() {
        var uri = resolve("/v1/snapshot/full");
        var response = send(uri, BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            throw failureFor(uri, response.statusCode(), readAll(uri, response.body()));
        }
        // Two resources, not one: `body` is fully initialized before `gzip`'s constructor runs, so
        // it is registered for close() even if that constructor throws on a malformed gzip header
        // (e.g. a bad magic number) — otherwise the pooled connection behind it would leak on every
        // resync against a service returning a broken envelope.
        try (var body = response.body(); var gzip = new GZIPInputStream(body)) {
            return FullSnapshotReader.read(gzip);
        } catch (IOException e) {
            throw new FeedUnavailableException(
                "Entitlement feed at " + uri + " answered 200 but the body was not valid gzip.", e);
        } catch (RuntimeException e) {
            // Covers FullSnapshotReader.MalformedFeedException and an UncheckedIOException from a
            // connection that dropped mid-stream — both mean "this feed body cannot be trusted",
            // which is exactly what FeedUnavailableException means to a caller. A caller that
            // catches only transport failure must see this, not a class it has never heard of.
            throw new FeedUnavailableException(
                "Entitlement feed at " + uri + " returned a snapshot that could not be applied.", e);
        }
    }

    /** {@code GET /v1/snapshot?since=} — everything needed to move a replica from {@code since} to current. */
    public DeltaDtos.DeltaResponse delta(long since) {
        var uri = resolve("/v1/snapshot?since=" + since);
        var response = send(uri, BodyHandlers.ofString());
        requireSuccess(uri, response.statusCode(), response.body());
        return parse(uri, response.body(), DeltaDtos.DeltaResponse.class);
    }

    /**
     * {@code GET /v1/accounts/{account}/capabilities/{capability}} — the raw response body, kept
     * unparsed because only the caller knows the trace shape; this package understands the network
     * and nothing about the domain.
     */
    public String decisionJson(String account, String capability) {
        var uri = resolve("/v1/accounts/" + encodePathSegment(account) + "/capabilities/" + encodePathSegment(capability));
        var response = send(uri, BodyHandlers.ofString());
        requireSuccess(uri, response.statusCode(), response.body());
        return response.body();
    }

    /**
     * As {@link #decisionJson}, but parsed into the trace-carrying wire shape and with the three
     * §6.3 domain errors surfaced as themselves rather than folded into {@link
     * FeedUnavailableException} — the caller (the diagnostic {@code explain()} path and the
     * read-through inside {@code check()}) needs to tell "the service does not know this account"
     * from "the service could not be reached" apart.
     */
    public DecisionDtos.DecisionResponse decision(String account, String capability) {
        var uri = resolve("/v1/accounts/" + encodePathSegment(account) + "/capabilities/" + encodePathSegment(capability));
        var response = send(uri, BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw decisionFailureFor(uri, response.statusCode(), response.body(), account, capability);
        }
        try {
            return ClientJson.MAPPER.readValue(response.body(), DecisionDtos.DecisionResponse.class);
        } catch (RuntimeException e) {
            throw new FeedUnavailableException(
                "Entitlement feed at " + uri + " answered 200 with a decision body that could not be parsed.", e);
        }
    }

    private RuntimeException decisionFailureFor(URI uri, int status, String body, String account, String capability) {
        ProblemDto problem;
        try {
            problem = ClientJson.MAPPER.readValue(body, ProblemDto.class);
        } catch (RuntimeException e) {
            return new FeedUnavailableException(
                "Entitlement feed at " + uri + " answered HTTP " + status + " with a body that was not a problem.",
                e);
        }
        if ("entitlement/unknown-account".equals(problem.type())) {
            return new UnknownAccountException(account);
        }
        if ("entitlement/unknown-capability".equals(problem.type())) {
            return new UnknownCapabilityException(capability);
        }
        if ("entitlement/retired-capability".equals(problem.type())) {
            return new RetiredCapabilityException(capability);
        }
        return new FeedUnavailableException("Entitlement feed at " + uri + " answered HTTP " + status
            + (problem.detail() != null ? ": " + problem.detail() : "."));
    }

    @Override
    public void close() {
        if (ownsHttpClient) {
            http.close();
        }
    }

    private void requireSuccess(URI uri, int statusCode, String body) {
        if (statusCode / 100 != 2) {
            throw failureFor(uri, statusCode, body);
        }
    }

    private <T> HttpResponse<T> send(URI uri, HttpResponse.BodyHandler<T> handler) {
        var request = HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .header("Accept-Encoding", "gzip")
            .GET()
            .build();
        try {
            return http.send(request, handler);
        } catch (IOException e) {
            throw new FeedUnavailableException("Could not reach the entitlement feed at " + uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FeedUnavailableException("Interrupted while waiting for the entitlement feed at " + uri, e);
        }
    }

    private static String readAll(URI uri, InputStream body) {
        try (body) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new FeedUnavailableException(
                "Entitlement feed at " + uri + " answered with a failure this SDK could not read.", e);
        }
    }

    private RuntimeException failureFor(URI uri, int status, String body) {
        ProblemDto problem;
        try {
            problem = ClientJson.MAPPER.readValue(body, ProblemDto.class);
        } catch (RuntimeException e) {
            return new FeedUnavailableException(
                "Entitlement feed at " + uri + " answered HTTP " + status + " with a body that was not a problem.",
                e);
        }
        if (status == 410 && "entitlement/snapshot-too-old".equals(problem.type())) {
            return new SnapshotTooOldException(
                "Snapshot too old at " + uri + "; the replica must fetch a full snapshot.");
        }
        if (status == 422 && problem.currentVersion() != null) {
            return new SnapshotTooOldException("Since is ahead of the service's current version ("
                + problem.currentVersion() + ") at " + uri + "; the replica must fetch a full snapshot.");
        }
        return new FeedUnavailableException("Entitlement feed at " + uri + " answered HTTP " + status
            + (problem.detail() != null ? ": " + problem.detail() : "."));
    }

    private static <T> T parse(URI uri, String body, Class<T> type) {
        try {
            return ClientJson.MAPPER.readValue(body, type);
        } catch (RuntimeException e) {
            throw new FeedUnavailableException(
                "Entitlement feed at " + uri + " answered 200 with a body that could not be parsed.", e);
        }
    }

    private URI resolve(String pathAndQuery) {
        return URI.create(baseUri.toString() + pathAndQuery);
    }

    /**
     * Percent-encodes one path segment. {@link URLEncoder} is form encoding, not path encoding —
     * it renders a space as {@code +}, which a path must not contain literally — so {@code +} is
     * corrected to {@code %20} afterwards.
     */
    private static String encodePathSegment(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
