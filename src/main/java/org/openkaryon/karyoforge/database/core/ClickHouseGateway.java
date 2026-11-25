package org.openkaryon.karyoforge.database.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** A bounded HTTP route to one explicit ClickHouse authority. */
final class ClickHouseGateway implements AutoCloseable {
    private static final int REQUEST_BYTES = 8 * 1024 * 1024;
    private static final long RESPONSE_BYTES = 128L * 1024 * 1024;
    private static final int HEADER_CHARS = 64 * 1024;
    private static final List<String> REQUEST_HEADERS = List.of("Authorization", "Content-Type", "Content-Encoding",
            "Accept", "Accept-Encoding", "X-ClickHouse-User", "X-ClickHouse-Key", "X-ClickHouse-Query-Id",
            "X-ClickHouse-Database", "X-ClickHouse-Format", "X-ClickHouse-Timezone");
    private static final List<String> RESPONSE_HEADERS = List.of("Content-Type", "Content-Encoding", "X-ClickHouse-Format",
            "X-ClickHouse-Timezone", "X-ClickHouse-Query-Id", "X-ClickHouse-Summary", "X-ClickHouse-Server-Display-Name",
            "X-ClickHouse-Exception-Code");
    private final HttpClient upstream;
    private final int querySeconds;
    private final java.util.concurrent.ExecutorService requests =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("KaryoForge ClickHouse HTTP-", 1).factory());
    private final java.util.concurrent.ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon().name("KaryoForge ClickHouse timeout").factory());
    private final Semaphore slots = new Semaphore(8);
    private final Map<String, Permit> permits = new ConcurrentHashMap<>();
    private final String authority;
    private final HttpServer route;

    ClickHouseGateway(int port, DatabaseTimeouts timeouts) throws IOException {
        upstream = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeouts.connectSeconds()))
                .followRedirects(HttpClient.Redirect.NEVER).proxy(ProxySelector.of(null)).version(HttpClient.Version.HTTP_1_1).build();
        querySeconds = timeouts.querySeconds();
        authority = "http://127.0.0.1:" + port;
        route = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 16);
        route.createContext("/", this::receive);
        route.setExecutor(requests);
        route.start();
    }

    int localPort() { return route.getAddress().getPort(); }

    Permit issue(boolean releaseAfterResponse) {
        if (permits.size() >= 128) throw new DatabaseProblem("The owned ClickHouse request limit was reached. Reconnect this source.");
        var permit = new Permit(UUID.randomUUID().toString(), releaseAfterResponse);
        permits.put(permit.id(), permit);
        return permit;
    }

    void retire(Permit permit) {
        permit.retire();
        permits.remove(permit.id(), permit);
    }

    private void receive(HttpExchange exchange) {
        if (!slots.tryAcquire()) {
            reject(exchange, 503);
            return;
        }
        Permit permit = null;
        var response = new AtomicReference<InputStream>();
        var expired = new AtomicBoolean();
        var timeout = deadlines.schedule(() -> {
            expired.set(true);
            InputStream stream = response.get();
            if (stream != null) {
                try { stream.close(); } catch (IOException ignored) {}
            }
            exchange.close();
        }, querySeconds + 7, TimeUnit.SECONDS);
        try {
            URI incoming = exchange.getRequestURI();
            String path = incoming.getRawPath();
            String target = incoming.toASCIIString();
            String method = exchange.getRequestMethod();
            if (incoming.isAbsolute() || incoming.getRawAuthority() != null || incoming.getRawFragment() != null
                    || target.length() + headerChars(exchange.getRequestHeaders()) > HEADER_CHARS || !"/".equals(path) || !"POST".equals(method)) {
                reject(exchange, 400);
                return;
            }
            String id = queryId(incoming.getRawQuery());
            List<String> header = exchange.getRequestHeaders().get("X-ClickHouse-Query-Id");
            Permit recorded = id == null ? null : permits.get(id);
            if (recorded == null || header == null || header.size() != 1 || !id.equals(header.getFirst()) || !recorded.begin()) {
                reject(exchange, 403);
                return;
            }
            permit = recorded;
            byte[] bytes = exchange.getRequestBody().readNBytes(REQUEST_BYTES + 1);
            if (bytes.length > REQUEST_BYTES) {
                reject(exchange, 413);
                return;
            }
            var outgoing = HttpRequest.newBuilder(URI.create(authority + target)).timeout(Duration.ofSeconds(querySeconds + 4));
            var supplied = new AtomicBoolean();
            outgoing.POST(HttpRequest.BodyPublishers.ofInputStream(() ->
                    supplied.compareAndSet(false, true) ? new ByteArrayInputStream(bytes) : null));
            for (String name : REQUEST_HEADERS) {
                List<String> values = exchange.getRequestHeaders().get(name);
                if (values != null && values.size() == 1) outgoing.header(name, values.getFirst());
            }
            if (!permit.reserveDispatch()) {
                reject(exchange, 403);
                return;
            }
            var result = upstream.send(outgoing.build(), HttpResponse.BodyHandlers.ofInputStream());
            response.set(result.body());
            try (var body = result.body()) {
                if (result.headers().firstValueAsLong("Content-Length").orElse(0) > RESPONSE_BYTES
                        || headerChars(result.headers().map()) > HEADER_CHARS) {
                    if (permit != null) permit.fail();
                    reject(exchange, 502);
                    return;
                }
                if (result.statusCode() >= 300 && result.statusCode() < 400) {
                    reject(exchange, 502);
                    return;
                }
                for (String name : RESPONSE_HEADERS) {
                    result.headers().firstValue(name).ifPresent(value -> exchange.getResponseHeaders().set(name, value));
                }
                exchange.sendResponseHeaders(result.statusCode(), 0);
                copyBounded(body, exchange);
            }
        } catch (ResponseLimit limit) {
            if (permit != null) {
                permit.fail();
                permit.uncertain();
            }
            reject(exchange, 502);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (permit != null) permit.uncertain();
            reject(exchange, 503);
        } catch (IOException | IllegalArgumentException unavailable) {
            if (permit != null) permit.uncertain();
            reject(exchange, 502);
        } finally {
            if (permit != null) {
                if (expired.get()) {
                    permit.fail();
                    permit.uncertain();
                }
                permit.settled();
                if (permit.releaseAfterResponse()) retire(permit);
            }
            response.set(null);
            timeout.cancel(false);
            exchange.close();
            slots.release();
        }
    }

    private static int headerChars(Map<String, List<String>> headers) {
        long total = 0;
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            total += header.getKey().length();
            for (String value : header.getValue()) total += value.length();
            if (total > HEADER_CHARS) return HEADER_CHARS + 1;
        }
        return (int) total;
    }

    private static String queryId(String query) {
        if (query == null) return null;
        String id = null;
        for (String pair : query.split("&")) {
            int equal = pair.indexOf('=');
            String key = URLDecoder.decode(equal < 0 ? pair : pair.substring(0, equal), StandardCharsets.UTF_8);
            if (!key.equals("query_id")) continue;
            if (id != null || equal < 0) return null;
            id = URLDecoder.decode(pair.substring(equal + 1), StandardCharsets.UTF_8);
        }
        if (id == null || id.length() != 36) return null;
        try {
            return UUID.fromString(id).toString().equals(id) ? id : null;
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private static void copyBounded(InputStream body, HttpExchange exchange) throws IOException {
        long length = 0;
        byte[] buffer = new byte[64 * 1024];
        var output = exchange.getResponseBody();
        for (int read; (read = body.read(buffer)) >= 0;) {
            length += read;
            if (length > RESPONSE_BYTES) throw new ResponseLimit();
            output.write(buffer, 0, read);
        }
    }

    private static void reject(HttpExchange request, int status) {
        try { request.sendResponseHeaders(status, -1); }
        catch (IOException ignored) {}
        finally { request.close(); }
    }

    @Override public void close() {
        for (Permit permit : permits.values()) retire(permit);
        route.stop(0);
        deadlines.shutdownNow();
        requests.shutdownNow();
        upstream.shutdownNow();
    }

    boolean awaitClosed() {
        try {
            return requests.awaitTermination(2, TimeUnit.SECONDS)
                    && deadlines.awaitTermination(2, TimeUnit.SECONDS)
                    && upstream.awaitTermination(Duration.ofSeconds(2));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static final class ResponseLimit extends IOException {}

    static final class Permit {
        private final String id;
        private final boolean releaseAfterResponse;
        private boolean used;
        private boolean inFlight;
        private boolean retired;
        private boolean dispatched;
        private boolean failed;
        private boolean uncertain;

        Permit(String id, boolean releaseAfterResponse) {
            this.id = id;
            this.releaseAfterResponse = releaseAfterResponse;
        }

        String id() { return id; }
        boolean releaseAfterResponse() { return releaseAfterResponse; }

        synchronized boolean begin() {
            if (retired || used) return false;
            used = true;
            inFlight = true;
            return true;
        }

        synchronized void retire() { retired = true; }
        synchronized void fail() { failed = true; }
        synchronized boolean failed() { return failed; }
        synchronized void settled() { inFlight = false; }
        synchronized boolean reserveDispatch() {
            if (retired) return false;
            dispatched = true;
            return true;
        }
        synchronized boolean dispatched() { return dispatched; }
        synchronized void uncertain() { uncertain = dispatched; }
        synchronized boolean needsProof() { return dispatched && (inFlight || uncertain); }
        synchronized boolean inFlight() { return inFlight; }
    }
}
