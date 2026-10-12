package forge.relay.client;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class ConnectionAttemptTest {
    @Test(timeOut = 10_000)
    public void cancellingAnUnresponsiveTlsHandshakeClosesTheSocket() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            final CountDownLatch securing = new CountDownLatch(1);
            final ConnectionAttempt attempt = new ConnectionAttempt(stage -> {
                if (stage == ConnectionAttempt.Stage.SECURING) securing.countDown();
            });
            var executor = Executors.newSingleThreadExecutor();
            try {
                var result = executor.submit(() -> {
                    try {
                        attempt.run(() -> RelayLobbyClient.listRooms(
                                new RelayEndpoint("127.0.0.1", server.getLocalPort(), true), "test"));
                        return false;
                    } catch (Exception expected) {
                        return true;
                    }
                });
                try (Socket peer = server.accept()) {
                    Assert.assertTrue(securing.await(3, TimeUnit.SECONDS));
                    attempt.cancel();
                    Assert.assertTrue(result.get(3, TimeUnit.SECONDS));
                    Assert.assertTrue(attempt.isCancelled());
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    public void failureCleansResourcesButIsNotReportedAsUserCancellation() {
        AtomicBoolean closed = new AtomicBoolean();
        ConnectionAttempt attempt = new ConnectionAttempt(stage -> { });
        Assert.expectThrows(IOException.class, () -> attempt.run(() -> {
            ConnectionAttempt.track((AutoCloseable) () -> closed.set(true));
            throw new IOException("server unavailable");
        }));
        Assert.assertTrue(closed.get());
        Assert.assertFalse(attempt.isCancelled());
    }

    @Test
    public void successfulConnectionTransfersOwnership() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        ConnectionAttempt attempt = new ConnectionAttempt(stage -> { });
        attempt.run(() -> ConnectionAttempt.track((AutoCloseable) () -> closed.set(true)));
        attempt.cancel();
        Assert.assertFalse(closed.get());
    }

    @Test
    public void cancelBeforeStartDoesNotRunTask() {
        ConnectionAttempt attempt = new ConnectionAttempt(stage -> { });
        attempt.cancel();
        Assert.expectThrows(IOException.class, () -> attempt.run(() -> {
            Assert.fail("Cancelled task must not create a room");
            return null;
        }));
    }
}
