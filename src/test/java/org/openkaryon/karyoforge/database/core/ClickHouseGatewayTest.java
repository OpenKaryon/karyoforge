package org.openkaryon.karyoforge.database.core;

import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public final class ClickHouseGatewayTest {
    @Test public void forwardsEachOwnedUuidAtMostOnceAndKeepsRedirectsInsideTheGuard() throws Exception {
        var calls = new AtomicInteger();
        var unexpected = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127,0,0,1}), 0), 4);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            if (exchange.getRequestURI().getRawQuery().contains("redirect=yes")) {
                exchange.getResponseHeaders().set("Location", "/redirect-target");
                exchange.sendResponseHeaders(307, -1);
            } else {
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write(new byte[]{'o', 'k'});
            }
            exchange.close();
        });
        server.createContext("/redirect-target", exchange -> {
            unexpected.incrementAndGet();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        try (var route = new ClickHouseGateway(server.getAddress().getPort());
             var client = HttpClient.newBuilder().proxy(ProxySelector.of(null)).build()) {
            var permit = route.issue(false);
            var path = "?query_id=" + permit.id();
            assertEquals(403, send(client, route, path, "00000000-0000-0000-0000-000000000000").statusCode());
            assertEquals(200, send(client, route, path).statusCode());
            assertEquals(403, send(client, route, path).statusCode());
            assertEquals(403, send(client, route, "?query_id=00000000-0000-0000-0000-000000000000").statusCode());
            assertEquals(1, calls.get());

            var retired = route.issue(false);
            route.retire(retired);
            assertEquals(403, send(client, route, "?query_id=" + retired.id()).statusCode());
            var redirect = route.issue(false);
            assertEquals(502, send(client, route, "?query_id=" + redirect.id() + "&redirect=yes").statusCode());
            assertEquals(0, unexpected.get());
        } finally {
            server.stop(0);
        }
    }

    private static HttpResponse<String> send(HttpClient client, ClickHouseGateway route, String suffix) throws Exception {
        return send(client, route, suffix, suffix.substring(10, 46));
    }

    private static HttpResponse<String> send(HttpClient client, ClickHouseGateway route, String suffix, String id) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + route.localPort() + "/" + suffix);
        return client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(4))
                .header("X-ClickHouse-Query-Id", id).POST(HttpRequest.BodyPublishers.ofString("SELECT 1")).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
