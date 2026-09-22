package forge.util;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.zip.ZipFile;

/** Disk-only startup checks. No network, skin cache, version string or GL state. */
public final class AndroidStartupFiles {
    public static final String INSTALLING = ".resources-installing";

    private AndroidStartupFiles() { }

    public static boolean hasUsableResources(Path assets) {
        return !Files.exists(assets.resolve(INSTALLING)) && hasCoreResources(assets);
    }

    public static boolean hasCoreResources(Path assets) {
        Path res = assets.resolve("res");
        for (String name : new String[] {"languages/en-US.properties", "skins/default/bg_splash.png",
                "skins/default/sprite_icons.png", "skins/default/font1.ttf"}) {
            if (!nonempty(res.resolve(name))) { return false; }
        }
        if (!containsTextFile(res.resolve("editions")) || !containsTextFile(res.resolve("tokenscripts"))) {
            return false;
        }
        Path cards = res.resolve("cardsfolder/cardsfolder.zip");
        if (Files.exists(cards)) {
            try (ZipFile zip = new ZipFile(cards.toFile())) {
                return zip.stream().anyMatch(e -> !e.isDirectory() && e.getName().endsWith(".txt") && e.getSize() > 0);
            } catch (IOException e) { return false; }
        }
        // Development/unpacked resource bundles are also supported.
        return containsTextFile(res.resolve("cardsfolder"));
    }

    private static boolean nonempty(Path file) {
        try { return Files.isRegularFile(file) && Files.size(file) > 0; }
        catch (IOException e) { return false; }
    }

    private static boolean containsTextFile(Path dir) {
        if (!Files.isDirectory(dir)) { return false; }
        try (var files = Files.walk(dir, 3)) {
            return files.anyMatch(f -> f.getFileName().toString().endsWith(".txt") && nonempty(f));
        } catch (IOException e) { return false; }
    }

    @FunctionalInterface
    public interface InputSource { InputStream open() throws IOException; }

    /** A killed/failed copy may leave a temporary file, never a truncated live file. */
    public static boolean install(InputSource source, Path target) throws IOException {
        byte[] expected;
        try (InputStream input = source.open()) { expected = digest(input); }
        if (Files.isRegularFile(target)) {
            try (InputStream input = Files.newInputStream(target)) {
                if (Arrays.equals(expected, digest(input))) { return false; }
            }
        }
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
        try {
            try (InputStream input = source.open(); FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) { output.write(buffer, 0, read); }
                output.getFD().sync();
            }
            try (InputStream input = Files.newInputStream(temporary)) {
                if (!Arrays.equals(expected, digest(input))) { throw new IOException("Bundled file changed while copying: " + target); }
            }
            // Same-directory rename. If the filesystem cannot do this atomically,
            // fail without deleting or truncating a working installation.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } finally { Files.deleteIfExists(temporary); }
    }

    private static byte[] digest(InputStream input) throws IOException {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) { hash.update(buffer, 0, read); }
            return hash.digest();
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
