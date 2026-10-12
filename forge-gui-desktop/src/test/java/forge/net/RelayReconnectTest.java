package forge.net;

import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.gui.GuiBase;
import forge.relay.RelayServer;
import forge.relay.client.RelayEndpoint;
import forge.relay.client.RelayGuestProxy;
import forge.relay.client.RelayHostSession;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

public class RelayReconnectTest {
    @Test(timeOut = 90_000)
    public void realClientReopensRelayAndReturnsToLobbyAfterDisconnectsAndSilentResume() throws Exception {
        TestUtils.ensureFModelInitialized();
        FServerManager server = FServerManager.getInstance();
        int port = PortAllocator.allocatePort();
        server.startRelayServer(port);
        server.setLobby(new ServerGameLobby(2, true));
        final AtomicReference<Throwable> hostFailure = new AtomicReference<>();
        try (RelayServer relay = new RelayServer("127.0.0.1", 0)) {
            relay.start();
            RelayEndpoint endpoint = new RelayEndpoint("127.0.0.1", relay.port());
            try (RelayHostSession host = RelayHostSession.open(endpoint, port,
                    "reconnect-test", "test room", "Host", "Constructed", "pw", 2, hostFailure::set)) {
                AtomicReference<RelayGuestProxy> proxy = new AtomicReference<>(
                        RelayGuestProxy.open(endpoint, "reconnect-test", host.roomId(), "pw"));
                AtomicInteger attempts = new AtomicInteger();
                try (FGameClient client = new FGameClient("ReconnectGuest", GuiBase.getInterface().getNewGuiGame(),
                        "127.0.0.1", proxy.get().localPort())) {
                    client.setExternalTransport(proxy.get());
                    client.setReconnectTransport(() -> {
                        attempts.incrementAndGet();
                        RelayGuestProxy replacement = RelayGuestProxy.open(
                                endpoint, "reconnect-test", host.roomId(), "pw");
                        proxy.set(replacement);
                        client.setExternalTransport(replacement);
                        return new InetSocketAddress("127.0.0.1", replacement.localPort());
                    });
                    client.connect();
                    client.awaitInitialLobby();
                    for (int i = 0; i < 2; i++) {
                        int previous = attempts.get();
                        proxy.get().close();
                        await(() -> attempts.get() > previous, "Reconnect must open a new tunnel");
                        await(() -> client.getReconnectState() == FGameClient.ReconnectState.CONNECTED,
                                "Lobby response must complete reconnection without an active match");
                    }
                    int previous = attempts.get();
                    client.simulateDisconnect(FGameClient.DisconnectMode.INBOUND);
                    client.onForeground();
                    await(() -> attempts.get() > previous,
                            "Foreground probe must detect a silent connection without the 45-second idle timeout");
                    await(() -> client.getReconnectState() == FGameClient.ReconnectState.CONNECTED,
                            "Silent connection must recover through a fresh relay tunnel");
                    Assert.assertNull(hostFailure.get(), "Guest disconnect must not close the room");
                }
            }
        } finally {
            server.stopServer();
        }
    }

    private static void await(final BooleanSupplier condition, final String message) throws Exception {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(25);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(25);
        Assert.assertTrue(condition.getAsBoolean(), message);
    }
}
