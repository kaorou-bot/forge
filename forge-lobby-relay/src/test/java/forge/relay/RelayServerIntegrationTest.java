package forge.relay;

import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class RelayServerIntegrationTest {
    private static final String COMPATIBILITY = "forge-cn-test-1";

    private RelayServer server;

    @BeforeMethod
    public void startServer() throws Exception {
        server = new RelayServer("127.0.0.1", 0);
        server.start();
    }

    @AfterMethod(alwaysRun = true)
    public void stopServer() {
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void registersListsPairsAndCleansUpRoom() throws Exception {
        try (Socket owner = connect()) {
            writeFrame(owner, RelayProtocol.REGISTER_ROOM, out -> {
                out.writeInt(RelayProtocol.VERSION);
                writeString(out, COMPATIBILITY);
                writeString(out, "测试房间");
                writeString(out, "房主");
                writeString(out, "Commander");
                writeString(out, "secret");
                out.writeInt(4);
            });

            DataInputStream registered = readFrame(owner, RelayProtocol.ROOM_REGISTERED);
            String roomId = readString(registered);
            String ownerToken = readString(registered);
            Assert.assertEquals(server.roomCount(), 1);

            try (Socket listClient = connect()) {
                writeFrame(listClient, RelayProtocol.LIST_ROOMS, out -> {
                    out.writeInt(RelayProtocol.VERSION);
                    writeString(out, COMPATIBILITY);
                });
                DataInputStream list = readFrame(listClient, RelayProtocol.ROOM_LIST);
                Assert.assertEquals(list.readInt(), 1);
                Assert.assertEquals(readString(list), roomId);
                Assert.assertEquals(readString(list), "测试房间");
                Assert.assertEquals(readString(list), "房主");
                Assert.assertEquals(readString(list), "Commander");
                Assert.assertEquals(readString(list), COMPATIBILITY);
                Assert.assertEquals(list.readInt(), 1);
                Assert.assertEquals(list.readInt(), 4);
                Assert.assertTrue(list.readBoolean());
            }

            try (Socket guest = connect(); Socket hostTunnel = connect()) {
                writeFrame(guest, RelayProtocol.JOIN_ROOM, out -> {
                    out.writeInt(RelayProtocol.VERSION);
                    writeString(out, COMPATIBILITY);
                    writeString(out, roomId);
                    writeString(out, "secret");
                });
                DataInputStream pending = readFrame(guest, RelayProtocol.JOIN_PENDING);
                String tunnelId = readString(pending);

                DataInputStream openTunnel = readFrame(owner, RelayProtocol.OPEN_TUNNEL);
                Assert.assertEquals(readString(openTunnel), roomId);
                Assert.assertEquals(readString(openTunnel), tunnelId);

                writeFrame(hostTunnel, RelayProtocol.HOST_TUNNEL, out -> {
                    out.writeInt(RelayProtocol.VERSION);
                    writeString(out, roomId);
                    writeString(out, ownerToken);
                    writeString(out, tunnelId);
                });
                readFrame(hostTunnel, RelayProtocol.TUNNEL_READY);
                readFrame(guest, RelayProtocol.TUNNEL_READY);

                byte[] guestPayload = "guest-to-host".getBytes(StandardCharsets.UTF_8);
                guest.getOutputStream().write(guestPayload);
                guest.getOutputStream().flush();
                Assert.assertEquals(hostTunnel.getInputStream().readNBytes(guestPayload.length), guestPayload);

                byte[] hostPayload = "host-to-guest".getBytes(StandardCharsets.UTF_8);
                hostTunnel.getOutputStream().write(hostPayload);
                hostTunnel.getOutputStream().flush();
                Assert.assertEquals(guest.getInputStream().readNBytes(hostPayload.length), hostPayload);

                owner.close();
                waitUntil(() -> server.roomCount() == 0, 3_000);
                Assert.assertEquals(server.roomCount(), 0);
                Assert.assertEquals(guest.getInputStream().read(), -1);
            }
        }
    }

    @Test
    public void rejectsWrongPasswordWithoutOpeningHostTunnel() throws Exception {
        try (Socket owner = connect(); Socket guest = connect()) {
            writeFrame(owner, RelayProtocol.REGISTER_ROOM, out -> {
                out.writeInt(RelayProtocol.VERSION);
                writeString(out, COMPATIBILITY);
                writeString(out, "Protected");
                writeString(out, "Owner");
                writeString(out, "Constructed");
                writeString(out, "correct-password");
                out.writeInt(2);
            });
            String roomId = readString(readFrame(owner, RelayProtocol.ROOM_REGISTERED));

            writeFrame(guest, RelayProtocol.JOIN_ROOM, out -> {
                out.writeInt(RelayProtocol.VERSION);
                writeString(out, COMPATIBILITY);
                writeString(out, roomId);
                writeString(out, "wrong-password");
            });
            DataInputStream error = readFrame(guest, RelayProtocol.ERROR);
            Assert.assertEquals(readString(error), "BAD_PASSWORD");
            Assert.assertFalse(readString(error).isBlank());
        }
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket("127.0.0.1", server.port());
        socket.setSoTimeout(5_000);
        socket.setTcpNoDelay(true);
        return socket;
    }

    private static void writeFrame(Socket socket, byte type, IoWriter writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream message = new DataOutputStream(bytes);
        message.writeByte(type);
        writer.write(message);
        message.flush();

        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        out.writeInt(bytes.size());
        bytes.writeTo(out);
        out.flush();
    }

    private static DataInputStream readFrame(Socket socket, byte expectedType) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        int length = in.readInt();
        if (length <= 0 || length > RelayProtocol.MAX_CONTROL_FRAME) {
            throw new IOException("Invalid frame length " + length);
        }
        byte[] payload = in.readNBytes(length);
        if (payload.length != length) {
            throw new EOFException("Short relay frame");
        }
        DataInputStream message = new DataInputStream(new ByteArrayInputStream(payload));
        Assert.assertEquals(message.readByte(), expectedType);
        return message;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static void waitUntil(Check check, long timeoutMillis) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!check.evaluate() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    @FunctionalInterface
    private interface IoWriter {
        void write(DataOutputStream out) throws IOException;
    }

    @FunctionalInterface
    private interface Check {
        boolean evaluate();
    }
}
