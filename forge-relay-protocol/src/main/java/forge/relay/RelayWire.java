package forge.relay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Blocking-stream counterpart to {@link RelayProtocol}, used by client proxies. */
public final class RelayWire {
    private RelayWire() {
    }

    public static void writeFrame(OutputStream stream, byte type, Writer writer) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream message = new DataOutputStream(bytes);
        message.writeByte(type);
        writer.write(message);
        message.flush();
        if (bytes.size() > RelayProtocol.MAX_CONTROL_FRAME) {
            throw new IOException("Relay control frame is too large");
        }

        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(bytes.size());
        bytes.writeTo(out);
        out.flush();
    }

    public static Frame readFrame(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        int length = in.readInt();
        if (length <= 0 || length > RelayProtocol.MAX_CONTROL_FRAME) {
            throw new IOException("Invalid relay frame length " + length);
        }
        byte[] payload = new byte[length];
        try {
            in.readFully(payload);
        } catch (EOFException e) {
            throw new EOFException("Relay closed during a control frame");
        }
        DataInputStream message = new DataInputStream(new ByteArrayInputStream(payload));
        return new Frame(message.readByte(), message);
    }

    public static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > RelayProtocol.MAX_STRING_BYTES) {
            throw new IOException("Relay control string is too long");
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    public static String readString(DataInputStream in) throws IOException {
        int length = in.readUnsignedShort();
        if (length > RelayProtocol.MAX_STRING_BYTES) {
            throw new IOException("Relay control string is too long");
        }
        byte[] bytes = new byte[length];
        try {
            in.readFully(bytes);
        } catch (EOFException e) {
            throw new EOFException("Relay closed during a control string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static void requireType(Frame frame, byte expected) throws IOException {
        if (frame.type() == RelayProtocol.ERROR) {
            String code = readString(frame.body());
            String detail = readString(frame.body());
            throw new RelayException(code, detail);
        }
        if (frame.type() != expected) {
            throw new IOException("Expected relay message " + expected + " but received " + frame.type());
        }
    }

    @FunctionalInterface
    public interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    public record Frame(byte type, DataInputStream body) {
    }

    public static final class RelayException extends IOException {
        private final String code;

        public RelayException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
