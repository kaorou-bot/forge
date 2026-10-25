package forge.download;

import forge.util.UpdateManifest;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class UpdateManifestTransportTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String TEXT = "schema=1\nversion=测试版本\nassets.url=assets/test.zip\n"
            + "assets.size=123\nassets.sha256=" + SHA + "\npublishedAt=完整清单\n";
    private static final byte[] BODY = TEXT.getBytes(StandardCharsets.UTF_8);

    /** Loopback only; no request can reach a real update server. Shared by download tests. */
    static final class LocalServer implements AutoCloseable {
        private final ServerSocket server;
        private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
        private final ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "forge-download-test-http");
            thread.setDaemon(true);
            return thread;
        });

        LocalServer(Handler handler) throws IOException {
            // Blocking TCP avoids the Windows JDK selector's internal Unix-domain pipe.
            server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            executor.submit(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket socket = server.accept();
                        connections.add(socket);
                        executor.submit(() -> {
                            try (socket; LocalExchange exchange = new LocalExchange(socket)) {
                                socket.setSoTimeout(5000);
                                BufferedReader reader = new BufferedReader(new InputStreamReader(
                                        socket.getInputStream(), StandardCharsets.US_ASCII));
                                String line;
                                while ((line = reader.readLine()) != null && !line.isEmpty()) { }
                                handler.handle(exchange);
                            } catch (IOException ignored) {
                                // Early client disconnects are intentional in cancellation/limit tests.
                            } finally {
                                connections.remove(socket);
                            }
                        });
                    } catch (IOException e) {
                        if (!server.isClosed()) { throw new IllegalStateException(e); }
                    }
                }
            });
        }

        String url() {
            return "http://127.0.0.1:" + server.getLocalPort() + "/manifest.properties";
        }

        @Override public void close() {
            try {
                server.close();
                for (Socket socket : connections) { socket.close(); }
            } catch (IOException e) {
                throw new AssertionError(e);
            }
            executor.shutdownNow();
            try {
                Assert.assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "HTTP fixture did not stop");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    @FunctionalInterface
    interface Handler {
        void handle(LocalExchange exchange) throws IOException;
    }

    static final class LocalExchange implements AutoCloseable {
        private final OutputStream wire;
        private OutputStream body;
        private boolean chunked;

        LocalExchange(Socket socket) throws IOException {
            wire = socket.getOutputStream();
        }

        void sendResponseHeaders(int status, long length) throws IOException {
            chunked = length == 0;
            String framing = chunked ? "Transfer-Encoding: chunked\r\n"
                    : "Content-Length: " + Math.max(0, length) + "\r\n";
            wire.write(("HTTP/1.1 " + status + " Test\r\nConnection: close\r\n" + framing + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            wire.flush();
            body = new OutputStream() {
                @Override public void write(int value) throws IOException {
                    write(new byte[] {(byte) value});
                }
                @Override public void write(byte[] data, int offset, int count) throws IOException {
                    if (count == 0) { return; }
                    if (chunked) {
                        wire.write((Integer.toHexString(count) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    }
                    wire.write(data, offset, count);
                    if (chunked) { wire.write("\r\n".getBytes(StandardCharsets.US_ASCII)); }
                }
                @Override public void flush() throws IOException { wire.flush(); }
            };
        }

        OutputStream getResponseBody() { return body; }

        @Override public void close() throws IOException {
            if (body != null && chunked) { wire.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); }
            wire.flush();
        }
    }

    static void respond(LocalExchange exchange, int status, byte[] body, boolean chunked) throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : chunked ? 0 : body.length);
        if (body.length > 0) {
            exchange.getResponseBody().write(body);
        }
    }

    static void awaitRelease(CountDownLatch release) {
        try {
            release.await(45, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test public void fullUtf8ManifestWorksWithKnownAndUnknownLength() throws Exception {
        for (boolean chunked : new boolean[] {false, true}) {
            try (LocalServer server = new LocalServer(e -> respond(e, 200, BODY, chunked))) {
                UpdateManifest manifest = UpdateManifest.load(server.url());
                Assert.assertEquals(manifest.version(), "测试版本");
                Assert.assertEquals(manifest.publishedAt(), "完整清单");
                Assert.assertEquals(manifest.assets().sha256(), SHA);
                Assert.assertEquals(manifest.assets().size(), 123L);
                Assert.assertTrue(manifest.resolveUrl(manifest.assets()).endsWith("/assets/test.zip"));
            }
        }
    }

    @Test public void nonSuccessHttpAndEmptyResponsesAreRejected() throws Exception {
        for (int status : new int[] {403, 500, 503}) {
            try (LocalServer server = new LocalServer(e -> respond(e, status, BODY, false))) {
                IOException error = Assert.expectThrows(IOException.class, () -> UpdateManifest.load(server.url()));
                Assert.assertTrue(error.getMessage().contains("HTTP " + status));
            }
        }
        for (byte[] body : new byte[][] {new byte[0], " \r\n\t".getBytes(StandardCharsets.UTF_8)}) {
            try (LocalServer server = new LocalServer(e -> respond(e, 200, body, false))) {
                IOException error = Assert.expectThrows(IOException.class, () -> UpdateManifest.load(server.url()));
                Assert.assertTrue(error.getMessage().contains("Empty update manifest"));
            }
        }
    }

    @Test public void truncatedButOtherwiseValidTextIsNotAccepted() throws Exception {
        try (LocalServer server = new LocalServer(e -> {
            e.sendResponseHeaders(200, BODY.length + 100);
            e.getResponseBody().write(BODY);
        })) {
            Assert.expectThrows(IOException.class, () -> UpdateManifest.load(server.url()));
        }
    }

    @Test public void oneMiBLimitIsEnforcedForFixedAndChunkedBodies() throws Exception {
        byte[] boundary = new byte[1024 * 1024];
        System.arraycopy(BODY, 0, boundary, 0, BODY.length);
        boundary[BODY.length] = '#';
        Arrays.fill(boundary, BODY.length + 1, boundary.length, (byte) 'x');
        try (LocalServer server = new LocalServer(e -> respond(e, 200, boundary, false))) {
            Assert.assertEquals(UpdateManifest.load(server.url()).version(), "测试版本");
        }
        byte[] excessive = Arrays.copyOf(boundary, boundary.length + 1);
        excessive[excessive.length - 1] = 'x';
        for (boolean chunked : new boolean[] {false, true}) {
            try (LocalServer server = new LocalServer(e -> respond(e, 200, excessive, chunked))) {
                IOException error = Assert.expectThrows(IOException.class, () -> UpdateManifest.load(server.url()));
                Assert.assertTrue(error.getMessage().contains("size limit"));
            }
        }
    }

    @Test public void retryAfterFailureWaitsForCompleteResponseBeyondOldFiveSecondLimit() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        try (LocalServer server = new LocalServer(e -> {
            if (requests.incrementAndGet() == 1) {
                respond(e, 503, new byte[0], false);
                return;
            }
            e.sendResponseHeaders(200, BODY.length);
            int prefix = "schema=1\nversion=测试版本\n".getBytes(StandardCharsets.UTF_8).length;
            e.getResponseBody().write(BODY, 0, prefix);
            e.getResponseBody().flush();
            try {
                Thread.sleep(5500);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException(ex);
            }
            e.getResponseBody().write(BODY, prefix, BODY.length - prefix);
        })) {
            Assert.expectThrows(IOException.class, () -> UpdateManifest.load(server.url()));
            long started = System.nanoTime();
            UpdateManifest manifest = UpdateManifest.load(server.url());
            Assert.assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= 5000);
            Assert.assertEquals(manifest.assets().sha256(), SHA);
            Assert.assertEquals(manifest.publishedAt(), "完整清单");
            Assert.assertEquals(requests.get(), 2);
        }
    }

    @Test public void stalledBodyTimesOutInsteadOfReturningPartialManifest() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (LocalServer server = new LocalServer(e -> {
            e.sendResponseHeaders(200, BODY.length + 1);
            e.getResponseBody().write(BODY);
            e.getResponseBody().flush();
            awaitRelease(release);
        })) {
            try {
                long started = System.nanoTime();
                Assert.expectThrows(SocketTimeoutException.class, () -> UpdateManifest.load(server.url()));
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                Assert.assertTrue(elapsed >= 14000 && elapsed < 30000, "Read timeout took " + elapsed + " ms");
            } finally {
                release.countDown();
            }
        }
    }
}
