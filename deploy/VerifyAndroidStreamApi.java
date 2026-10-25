import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Narrow release guard for the Android 8-13 Stream.toList regression.
 * Reads method references from every generated DEX, including dependencies.
 * This is not a complete Android API compatibility audit or a device test.
 */
public class VerifyAndroidStreamApi {
    private final byte[] bytes;
    private final ByteBuffer data;

    private VerifyAndroidStreamApi(Path path) throws IOException {
        bytes = Files.readAllBytes(path);
        if (bytes.length < 112 || bytes[0] != 'd' || bytes[1] != 'e' || bytes[2] != 'x'
                || bytes[3] != '\n' || bytes[7] != 0) {
            throw new IOException("Not a standard DEX: " + path);
        }
        data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (data.getInt(32) != bytes.length || data.getInt(36) != 112
                || data.getInt(40) != 0x12345678) {
            throw new IOException("Unsupported or truncated DEX header: " + path);
        }
    }

    private void bounds(long offset, long length) throws IOException {
        if (offset < 0 || length < 0 || offset + length > bytes.length) {
            throw new IOException("Invalid DEX range: " + offset + "+" + length);
        }
    }

    private int table(int countOffset, int offsetOffset, int width) throws IOException {
        final int count = data.getInt(countOffset);
        final int offset = data.getInt(offsetOffset);
        bounds(offset, (long) count * width);
        return count;
    }

    private String string(int index, int count, int table) throws IOException {
        if (index < 0 || index >= count) {
            throw new IOException("Invalid DEX string index: " + index);
        }
        int offset = data.getInt(table + index * 4);
        // Skip the UTF-16 length encoded as ULEB128. Names compared below are ASCII.
        int size = 0;
        do {
            bounds(offset, 1);
            if (++size > 5) throw new IOException("Invalid DEX ULEB128");
        } while ((bytes[offset++] & 0x80) != 0);
        final int start = offset;
        while (true) {
            bounds(offset, 1);
            if (bytes[offset] == 0) break;
            offset++;
        }
        return new String(bytes, start, offset - start, StandardCharsets.UTF_8);
    }

    private List<String> forbidden() throws IOException {
        final int strings = table(56, 60, 4);
        final int types = table(64, 68, 4);
        final int methods = table(88, 92, 8);
        final int stringTable = data.getInt(60);
        final int typeTable = data.getInt(68);
        final int methodTable = data.getInt(92);
        final List<String> found = new ArrayList<>();
        for (int i = 0; i < methods; i++) {
            final int entry = methodTable + i * 8;
            final int owner = Short.toUnsignedInt(data.getShort(entry));
            if (owner >= types) throw new IOException("Invalid DEX type index");
            final String name = string(data.getInt(entry + 4), strings, stringTable);
            if (!"toList".equals(name)) continue;
            final String type = string(data.getInt(typeTable + owner * 4), strings, stringTable);
            if ("Ljava/util/stream/Stream;".equals(type)) {
                found.add(type + "->" + name + " (method index " + i + ")");
            }
        }
        return found;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Usage: VerifyAndroidStreamApi.java <dex-directory>");
        final Path directory = Path.of(args[0]);
        final List<Path> dexFiles;
        try (var paths = Files.list(directory)) {
            dexFiles = paths.filter(p -> p.getFileName().toString().matches("classes[0-9]*\\.dex"))
                    .sorted().toList();
        }
        if (dexFiles.isEmpty()) throw new IOException("No classes*.dex in " + directory);
        final List<String> errors = new ArrayList<>();
        for (Path dex : dexFiles) {
            for (String method : new VerifyAndroidStreamApi(dex).forbidden()) {
                errors.add(dex.getFileName() + ": " + method);
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Android API 34-only Stream.toList reference; use "
                    + "StreamUtil.toImmutableList() for Android 8-13:\n" + String.join("\n", errors));
        }
        System.out.println("Android Stream API guard passed: " + dexFiles.size() + " DEX files, no platform Stream.toList references.");
    }
}
