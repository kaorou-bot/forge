package forge.relay;

import java.util.concurrent.CountDownLatch;

/** Command-line entry point for the central lobby/relay service. */
public final class RelayServerMain {
    private RelayServerMain() {
    }

    public static void main(String[] args) throws Exception {
        String bind = System.getProperty("forge.relay.bind", "127.0.0.1");
        int port = Integer.getInteger("forge.relay.port", 36744);
        String healthBind = System.getProperty("forge.relay.health.bind", "127.0.0.1");
        int healthPort = Integer.getInteger("forge.relay.health.port", 36745);
        RelayServer server = new RelayServer(bind, port);
        server.start();
        RelayHealthServer health = new RelayHealthServer(server, healthBind, healthPort);
        health.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            health.close();
            server.close();
        }, "forge-relay-shutdown"));
        new CountDownLatch(1).await();
    }
}
