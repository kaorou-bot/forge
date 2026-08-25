package forge.relay.client;

import forge.relay.RelayProtocol;
import forge.relay.RelayServer;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class RelayClientIntegrationTest {
    private RelayServer relayServer;
    private ServerSocket echoServer;
    private ExecutorService echoExecutor;

    @BeforeMethod
    public void startServers() throws Exception {
        relayServer = new RelayServer("127.0.0.1", 0);
        relayServer.start();
        echoServer = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        echoExecutor = Executors.newCachedThreadPool(RelayGuestProxy.daemonFactory("relay-test-echo"));
        echoExecutor.execute(this::acceptEchoConnections);
    }

    @AfterMethod(alwaysRun = true)
    public void stopServers() throws Exception {
        if (echoServer != null) {
            echoServer.close();
        }
        if (echoExecutor != null) {
            echoExecutor.shutdownNow();
        }
        if (relayServer != null) {
            relayServer.close();
        }
    }

    @Test
    public void hostAndGuestProxiesCarryOpaqueGameBytes() throws Exception {
        RelayEndpoint endpoint = new RelayEndpoint("127.0.0.1", relayServer.port());
        String compatibility = "cn-client-integration-1";
        try (RelayHostSession host = RelayHostSession.open(endpoint, echoServer.getLocalPort(),
                compatibility, "大厅测试", "房主", "Constructed", "pw", 4,
                error -> Assert.fail("Unexpected host failure", error))) {

            List<RelayProtocol.RoomSnapshot> rooms = RelayLobbyClient.listRooms(endpoint, compatibility);
            Assert.assertEquals(rooms.size(), 1);
            Assert.assertEquals(rooms.get(0).roomId(), host.roomId());
            Assert.assertTrue(rooms.get(0).passwordProtected());

            try (RelayGuestProxy guest = RelayGuestProxy.open(
                    endpoint, compatibility, host.roomId(), "pw");
                 Socket forgeClient = new Socket(InetAddress.getLoopbackAddress(), guest.localPort())) {
                forgeClient.setSoTimeout(5_000);
                byte[] payload = "opaque-forge-network-frame".getBytes(StandardCharsets.UTF_8);
                forgeClient.getOutputStream().write(payload);
                forgeClient.getOutputStream().flush();
                Assert.assertEquals(forgeClient.getInputStream().readNBytes(payload.length), payload);
            }
        }
    }

    private void acceptEchoConnections() {
        while (!echoServer.isClosed()) {
            try {
                Socket socket = echoServer.accept();
                echoExecutor.execute(() -> echo(socket));
            } catch (IOException e) {
                if (!echoServer.isClosed()) {
                    throw new RuntimeException(e);
                }
            }
        }
    }

    private static void echo(Socket socket) {
        byte[] buffer = new byte[8 * 1024];
        try (socket) {
            int read;
            while ((read = socket.getInputStream().read(buffer)) >= 0) {
                if (read > 0) {
                    socket.getOutputStream().write(buffer, 0, read);
                    socket.getOutputStream().flush();
                }
            }
        } catch (IOException ignored) {
            // Test cleanup closes active echo sockets.
        }
    }
}
