package forge.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** Wire helpers shared by the Forge clients and the central relay service. */
public final class RelayProtocol {
    public static final int VERSION = 1;
    public static final int MAX_CONTROL_FRAME = 64 * 1024;
    public static final int MAX_STRING_BYTES = 1024;

    public static final byte REGISTER_ROOM = 1;
    public static final byte ROOM_REGISTERED = 2;
    public static final byte LIST_ROOMS = 3;
    public static final byte ROOM_LIST = 4;
    public static final byte JOIN_ROOM = 5;
    public static final byte JOIN_PENDING = 6;
    public static final byte OPEN_TUNNEL = 7;
    public static final byte HOST_TUNNEL = 8;
    public static final byte TUNNEL_READY = 9;
    public static final byte ERROR = 10;
    public static final byte HEARTBEAT = 11;

    private RelayProtocol() {
    }

    public static ByteBuf message(byte type, Writer writer) {
        ByteBuf out = Unpooled.buffer();
        out.writeByte(type);
        writer.write(out);
        return out;
    }

    public static ByteBuf registered(String roomId, String ownerToken) {
        return message(ROOM_REGISTERED, out -> {
            writeString(out, roomId);
            writeString(out, ownerToken);
        });
    }

    public static ByteBuf roomList(List<RoomSnapshot> rooms) {
        return message(ROOM_LIST, out -> {
            out.writeInt(rooms.size());
            for (RoomSnapshot room : rooms) {
                writeString(out, room.roomId());
                writeString(out, room.name());
                writeString(out, room.ownerName());
                writeString(out, room.format());
                writeString(out, room.compatibilityVersion());
                out.writeInt(room.players());
                out.writeInt(room.maxPlayers());
                out.writeBoolean(room.passwordProtected());
            }
        });
    }

    public static ByteBuf joinPending(String tunnelId) {
        return message(JOIN_PENDING, out -> writeString(out, tunnelId));
    }

    public static ByteBuf openTunnel(String roomId, String tunnelId) {
        return message(OPEN_TUNNEL, out -> {
            writeString(out, roomId);
            writeString(out, tunnelId);
        });
    }

    public static ByteBuf tunnelReady() {
        return message(TUNNEL_READY, out -> { });
    }

    public static ByteBuf error(String code, String detail) {
        return message(ERROR, out -> {
            writeString(out, code);
            writeString(out, detail);
        });
    }

    public static void requireProtocol(ByteBuf in) throws ProtocolException {
        int version = readInt(in, "protocol version");
        if (version != VERSION) {
            throw new ProtocolException("INCOMPATIBLE_RELAY_PROTOCOL",
                    "Expected relay protocol " + VERSION + " but received " + version);
        }
    }

    public static int readInt(ByteBuf in, String field) throws ProtocolException {
        if (in.readableBytes() < Integer.BYTES) {
            throw new ProtocolException("MALFORMED_REQUEST", "Missing " + field);
        }
        return in.readInt();
    }

    public static boolean readBoolean(ByteBuf in, String field) throws ProtocolException {
        if (!in.isReadable()) {
            throw new ProtocolException("MALFORMED_REQUEST", "Missing " + field);
        }
        return in.readBoolean();
    }

    public static String readString(ByteBuf in, String field) throws ProtocolException {
        if (in.readableBytes() < Short.BYTES) {
            throw new ProtocolException("MALFORMED_REQUEST", "Missing " + field);
        }
        int length = in.readUnsignedShort();
        if (length > MAX_STRING_BYTES || in.readableBytes() < length) {
            throw new ProtocolException("MALFORMED_REQUEST", "Invalid " + field + " length");
        }
        return in.readCharSequence(length, StandardCharsets.UTF_8).toString();
    }

    public static void writeString(ByteBuf out, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("Control string exceeds " + MAX_STRING_BYTES + " bytes");
        }
        out.writeShort(bytes.length);
        out.writeBytes(bytes);
    }

    public static String hexToken(byte[] token) {
        return ByteBufUtil.hexDump(token);
    }

    @FunctionalInterface
    public interface Writer {
        void write(ByteBuf out);
    }

    public static final class ProtocolException extends Exception {
        private final String code;

        public ProtocolException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    public record RoomSnapshot(String roomId, String name, String ownerName, String format,
                               String compatibilityVersion, int players, int maxPlayers,
                               boolean passwordProtected) {
    }
}
