package forge.relay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loopback-only health endpoint intended for local monitoring and load balancers. */
final class RelayHealthServer implements AutoCloseable {
    private final RelayServer relay;
    private final HttpServer http;
    private final ExecutorService executor;

    RelayHealthServer(RelayServer relay, String bindAddress, int port) throws IOException {
        this.relay = relay;
        this.http = HttpServer.create(new InetSocketAddress(bindAddress, port), 8);
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "forge-relay-health");
            thread.setDaemon(true);
            return thread;
        });
        http.setExecutor(executor);
        http.createContext("/health", this::health);
    }

    void start() {
        http.start();
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(405, -1);
            exchange.close();
            return;
        }
        byte[] response = String.format(
                "{\"status\":\"ok\",\"rooms\":%d,\"connections\":%d}\n",
                relay.roomCount(), relay.connectionCount()).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    @Override
    public void close() {
        http.stop(0);
        executor.shutdownNow();
    }
}
