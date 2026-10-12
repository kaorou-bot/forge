package forge.gamemodes.net;

import forge.StaticData;
import forge.deck.Deck;
import forge.game.Game;
import forge.game.player.Player;
import forge.gamemodes.match.LobbySlotType;
import forge.gamemodes.net.client.FGameClient;
import forge.gamemodes.net.server.FServerManager;
import forge.gamemodes.net.server.ServerGameLobby;
import forge.net.HeadlessNetworkGuiGame;
import forge.net.PortAllocator;
import forge.net.RelayReconnectTest;
import forge.net.TestUtils;
import forge.relay.RelayServer;
import forge.relay.client.RelayEndpoint;
import forge.relay.client.RelayGuestProxy;
import forge.relay.client.RelayHostSession;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Real loopback TCP relay, lobby, HostedMatch, client reconnect and operation budget. Headless GUI. */
@Test(singleThreaded = true, timeOut = 60000)
public class ClockTransportIntegrationTest {
    private final TimedGameDecisionsTest model = new TimedGameDecisionsTest();

    @BeforeClass public void initializeIsolatedModel() {
        model.initializeIsolatedModelOutsideTestTimeout();
        TestUtils.ensureFModelInitialized();
        FServerManager.getInstance().setLobbyListener(new forge.interfaces.ILobbyListener() {
            @Override public void message(final String source, final String message,
                    final forge.gamemodes.net.ChatMessage.MessageType type) { }
            @Override public void update(final forge.gamemodes.match.GameLobby.GameLobbyData state, final int slot) { }
            @Override public void close() { }
            @Override public forge.gamemodes.net.client.ClientGameLobby getLobby() { return null; }
        });
    }

    @AfterClass(alwaysRun = true) public void restoreGui() { model.restoreGui(); }

    public void realRelayLobbyReconnectAndSilentResume() throws Exception {
        new RelayReconnectTest().realClientReopensRelayAndReturnsToLobbyAfterDisconnectsAndSilentResume();
    }

    public void realTimedMatchReconnectKeepsBudgetAndEventuallyLosesTheWaitingPlayer() throws Exception {
        final FServerManager server = FServerManager.getInstance();
        final int port = PortAllocator.allocatePort();
        final ServerGameLobby lobby = new ServerGameLobby(2, true);
        final AtomicReference<Throwable> hostFailure = new AtomicReference<>();
        final AtomicReference<Game> startedGame = new AtomicReference<>();
        server.startRelayServer(port);
        server.setLobby(lobby);
        lobby.setOperationTimeLimitMillis(10000);
        for (int i = 0; i < 2; i++) {
            final Deck deck = new Deck("Clock transport " + i);
            deck.getMain().add(StaticData.instance().getCommonCards().getCard("Plains"), 60);
            lobby.getSlot(i).setDeck(deck);
            lobby.getSlot(i).setType(i == 0 ? LobbySlotType.AI : LobbySlotType.OPEN);
            lobby.getSlot(i).setName(i == 0 ? "ClockHost" : "ClockGuest");
            lobby.getSlot(i).setIsReady(i == 0);
        }
        try (RelayServer relay = new RelayServer("127.0.0.1", 0)) {
            relay.start();
            final RelayEndpoint endpoint = new RelayEndpoint("127.0.0.1", relay.port());
            try (RelayHostSession host = RelayHostSession.open(endpoint, port, "clock-round12",
                    "Timed integration", "ClockHost", "Constructed", "test-only", 2, hostFailure::set)) {
                final AtomicReference<RelayGuestProxy> proxy = new AtomicReference<>(
                        RelayGuestProxy.open(endpoint, "clock-round12", host.roomId(), "test-only"));
                final AtomicInteger attempts = new AtomicInteger();
                try (FGameClient client = new FGameClient("ClockGuest", new HeadlessNetworkGuiGame(),
                        "127.0.0.1", proxy.get().localPort())) {
                    client.setExternalTransport(proxy.get());
                    client.setReconnectTransport(() -> {
                        attempts.incrementAndGet();
                        final RelayGuestProxy replacement = RelayGuestProxy.open(endpoint, "clock-round12", host.roomId(), "test-only");
                        proxy.set(replacement);
                        client.setExternalTransport(replacement);
                        return new InetSocketAddress("127.0.0.1", replacement.localPort());
                    });
                    client.connect();
                    client.awaitInitialLobby();
                    await(() -> server.getClientBySlotIndex(1) != null
                            && server.getClientBySlotIndex(1).supportsOperationClock(), 5000, "Clock negotiation");
                    lobby.getSlot(1).setIsReady(true);
                    final Runnable start = lobby.startGame();
                    Assert.assertNotNull(start, "Real lobby must accept this test match");
                    start.run();
                    final Game game = lobby.getHostedMatch().getGame();
                    startedGame.set(game);
                    final Player guest = game.getPlayers().stream().filter(p -> p.getName().equals("ClockGuest"))
                            .findFirst().orElseThrow();
                    await(() -> TimedGameDecisions.forGame(game) != null
                            && snapshot(game, guest).running(), 12000, "Actual remote input must become ready");
                    TimeUnit.MILLISECONDS.sleep(800);
                    final long beforeReconnect = snapshot(game, guest).remainingMillis();
                    Assert.assertTrue(beforeReconnect < 9800 && beforeReconnect > 1000);
                    proxy.get().close();
                    await(() -> attempts.get() > 0 && client.getReconnectState() == FGameClient.ReconnectState.CONNECTED,
                            7000, "Active timed match must reopen the relay transport");
                    final long afterReconnect = snapshot(game, guest).remainingMillis();
                    Assert.assertTrue(afterReconnect < beforeReconnect, "Disconnect cannot pause or reset the budget");
                    Assert.assertTrue(afterReconnect > 0, "Reconnect must finish before this test's timeout");
                    await(guest::hasLost, 12000, "Waiting remote player must lose when the same budget reaches zero");
                    await(game::isGameOver, 3000, "Two-player match must end after timeout");
                    Assert.assertTrue(guest.conceded());
                    Assert.assertNull(hostFailure.get(), "Guest reconnect must not kill the relay room");
                    System.out.println("CLOCK_TRANSPORT allowance=10000 beforeReconnect=" + beforeReconnect
                            + " afterReconnect=" + afterReconnect + " attempts=" + attempts.get() + " loser=" + guest.getName());
                }
            }
        } finally {
            final Game game = startedGame.get();
            if (game != null && TimedGameDecisions.forGame(game) != null) TimedGameDecisions.forGame(game).close();
            server.stopServer();
        }
    }

    private static PlayerOperationClock.Snapshot snapshot(final Game game, final Player player) {
        try {
            final java.lang.reflect.Field field = TimedGameDecisions.class.getDeclaredField("clock");
            field.setAccessible(true);
            return ((PlayerOperationClock) field.get(TimedGameDecisions.forGame(game))).snapshot(player.getId());
        } catch (final ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static void await(final BooleanSupplier condition, final long millis, final String message) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) TimeUnit.MILLISECONDS.sleep(25);
        Assert.assertTrue(condition.getAsBoolean(), message);
    }
}
