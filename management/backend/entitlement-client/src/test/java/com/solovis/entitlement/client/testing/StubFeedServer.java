package com.solovis.entitlement.client.testing;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;

/**
 * An in-process stand-in for the management service's feed, so outage, truncation and
 * malformed-response behaviour can be tested without a network or a Spring context.
 */
public final class StubFeedServer implements AutoCloseable {

    private final HttpServer server;
    private final AtomicReference<String> versionBody = new AtomicReference<>(
        "{\"version\":1,\"publishedAt\":\"2026-08-09T14:00:00.000Z\",\"format\":1,\"resolverContract\":1}");
    private final AtomicReference<String> fullBody = new AtomicReference<>("");
    private final AtomicReference<String> deltaBody = new AtomicReference<>(null);
    private final AtomicReference<String> decisionBody = new AtomicReference<>(null);
    private final AtomicReference<int[]> failure = new AtomicReference<>(null);   // {status} for the next call
    private final AtomicReference<String> failureBody = new AtomicReference<>(null);
    private final AtomicInteger versionCalls = new AtomicInteger();
    private final AtomicInteger fullCalls = new AtomicInteger();
    private final AtomicInteger deltaCalls = new AtomicInteger();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private volatile boolean truncateFull = false;

    public StubFeedServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/snapshot/version", this::handleVersion);
        server.createContext("/v1/snapshot/full", this::handleFull);
        server.createContext("/v1/snapshot", this::handleDelta);
        server.createContext("/v1/accounts", this::handleDecision);
        server.setExecutor(null);
        server.start();
    }

    public URI baseUri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public void respondVersion(long version, String publishedAt, int format, int resolverContract) {
        versionBody.set("{\"version\":" + version + ",\"publishedAt\":\"" + publishedAt
            + "\",\"format\":" + format + ",\"resolverContract\":" + resolverContract + "}");
    }

    public void respondFull(String ndjson) {
        fullBody.set(ndjson);
    }

    public void respondDelta(String json) {
        deltaBody.set(json);
    }

    public void respondDecision(String json) {
        decisionBody.set(json);
    }

    /** The next request to any route answers with this status and problem+json body. */
    public void failWith(int status, String problemJson) {
        failure.set(new int[] {status});
        failureBody.set(problemJson);
    }

    /** Truncate the full-snapshot body before its footer, simulating a cut-off response. */
    public void truncateFullSnapshot() {
        truncateFull = true;
    }

    public int versionCalls() {
        return versionCalls.get();
    }

    public int fullCalls() {
        return fullCalls.get();
    }

    public int deltaCalls() {
        return deltaCalls.get();
    }

    public List<String> requestedPaths() {
        return List.copyOf(paths);
    }

    private boolean servedFailure(HttpExchange exchange) throws IOException {
        var pending = failure.getAndSet(null);
        if (pending == null) {
            return false;
        }
        var body = failureBody.getAndSet(null).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/problem+json");
        exchange.sendResponseHeaders(pending[0], body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
        return true;
    }

    private void handleVersion(HttpExchange exchange) throws IOException {
        versionCalls.incrementAndGet();
        paths.add(exchange.getRequestURI().toString());
        if (servedFailure(exchange)) {
            return;
        }
        respond(exchange, 200, "application/json", versionBody.get().getBytes(StandardCharsets.UTF_8));
    }

    private void handleFull(HttpExchange exchange) throws IOException {
        fullCalls.incrementAndGet();
        paths.add(exchange.getRequestURI().toString());
        if (servedFailure(exchange)) {
            return;
        }
        var text = fullBody.get();
        if (truncateFull) {
            int cut = text.lastIndexOf("\n{\"kind\":\"footer\"");
            text = cut > 0 ? text.substring(0, cut) : text;
        }
        var gzipped = gzip(text);
        exchange.getResponseHeaders().add("Content-Type", "application/x-ndjson");
        exchange.getResponseHeaders().add("Content-Encoding", "gzip");
        exchange.sendResponseHeaders(200, gzipped.length);
        exchange.getResponseBody().write(gzipped);
        exchange.close();
    }

    private void handleDelta(HttpExchange exchange) throws IOException {
        var path = exchange.getRequestURI().getPath();
        if (path.startsWith("/v1/snapshot/")) {   // /full and /version have their own contexts
            respond(exchange, 404, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        deltaCalls.incrementAndGet();
        paths.add(exchange.getRequestURI().toString());
        if (servedFailure(exchange)) {
            return;
        }
        var body = deltaBody.get();
        if (body == null) {
            respond(exchange, 500, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        respond(exchange, 200, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    private void handleDecision(HttpExchange exchange) throws IOException {
        paths.add(exchange.getRequestURI().toString());
        if (servedFailure(exchange)) {
            return;
        }
        var body = decisionBody.get();
        if (body == null) {
            respond(exchange, 500, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
            return;
        }
        respond(exchange, 200, "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static byte[] gzip(String text) throws IOException {
        var out = new ByteArrayOutputStream();
        try (var gz = new GZIPOutputStream(out)) {
            gz.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
