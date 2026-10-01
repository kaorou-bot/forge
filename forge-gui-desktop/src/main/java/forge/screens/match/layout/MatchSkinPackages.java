package forge.screens.match.layout;

import forge.localinstance.properties.ForgeConstants;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipInputStream;

/** Imports only data files, to an isolated package directory. No scripts, classes or URLs. */
public final class MatchSkinPackages {
    private MatchSkinPackages() { }
    public static Path directory() { return Path.of(ForgeConstants.USER_PREFS_DIR, "desktop-match-skins"); }
    public static List<String> installed() {
        return installed(directory());
    }
    static List<String> installed(Path destination) {
        if (!Files.isDirectory(destination)) { return List.of(); }
        try (var paths = Files.list(destination.toRealPath())) {
            return paths.filter(p -> {
                try { packagePath(p.getFileName().toString(), destination); return true; }
                catch (IOException e) { return false; }
            }).map(p -> p.getFileName().toString()).sorted().toList();
        } catch (IOException e) { return List.of(); }
    }
    public static MatchUiLayout load(String name) throws IOException {
        return read(packagePath(name, directory()));
    }
    private static Path packagePath(String name, Path destination) throws IOException {
        if (name == null || !name.matches("[a-z][a-z0-9-]{0,80}")) { throw new IOException("Select an imported skin first"); }
        final Path root = destination.toRealPath();
        final Path base = root.resolve(name);
        if (!Files.isDirectory(base, java.nio.file.LinkOption.NOFOLLOW_LINKS) || !base.toRealPath().equals(base)
                || !Files.isRegularFile(base.resolve("match-ui.json"), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Not a saved skin directory: " + base);
        }
        return base;
    }

    /** Call only after user confirmation and detaching this package from the current scene. */
    public static void remove(String name) throws IOException { remove(name, directory()); }

    static void remove(String name, Path destination) throws IOException {
        final Path base = packagePath(name, destination);
        final Path json = base.resolve("match-ui.json");
        final var files = new java.util.ArrayList<Path>();
        final var directories = new java.util.ArrayList<Path>();
        // Preflight the entire tree before deleting anything. Never follow symlinks or Windows junctions.
        Files.walkFileTree(base, new java.nio.file.SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                validateRemovalPath(base, dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                validateRemovalPath(base, file);
                if (!attrs.isRegularFile()) { throw new IOException("Unsupported entry in saved skin: " + file); }
                if (!file.equals(json)) { files.add(file); }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                if (error != null) { throw error; }
                if (!dir.equals(base)) { directories.add(dir); }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        // Keep the library entry until all assets were removed, so a failed deletion can be retried.
        files.addAll(directories);
        files.add(json);
        files.add(base);
        for (Path path : files) {
            validateRemovalPath(base, path);
            Files.delete(path);
        }
    }
    private static void validateRemovalPath(Path base, Path path) throws IOException {
        if (!path.startsWith(base) || Files.isSymbolicLink(path) || !path.toRealPath().equals(path)) {
            throw new IOException("Refusing to delete a linked or external skin path: " + path);
        }
    }
    private static MatchUiLayout read(Path base) throws IOException {
        final Path json = MatchSkinTheme.asset(base, "match-ui.json");
        if (Files.size(json) > 1024 * 1024) { throw new IOException("Skin configuration exceeds 1 MB"); }
        try (var reader = Files.newBufferedReader(json, StandardCharsets.UTF_8)) {
            final MatchUiLayout layout = MatchUiLayout.read(reader, base);
            if (layout.scene() == null) { throw new IOException("A match skin package needs a scene"); }
            return layout;
        } catch (RuntimeException e) { throw new IOException("Invalid skin package: " + e.getMessage(), e); }
    }
    public static String install(Path archive) throws IOException { return install(archive, directory()); }
    static String install(Path archive, Path destination) throws IOException {
        Files.createDirectories(destination);
        final Path staging = Files.createTempDirectory(destination, ".import-");
        boolean installed = false;
        try {
            long total = 0;
            int count = 0;
            final var names = new HashSet<String>();
            try (var zip = new ZipInputStream(Files.newInputStream(archive), StandardCharsets.UTF_8)) {
                java.util.zip.ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    final String name = entry.getName();
                    if (++count > 256 || name.isBlank() || name.contains("\\") || name.contains(":")
                            || name.startsWith("/") || !names.add(name.toLowerCase(Locale.ROOT))) {
                        throw new IOException("Invalid or duplicate package entry: " + name);
                    }
                    final Path path = staging.resolve(name).normalize();
                    if (!path.startsWith(staging) || path.equals(staging)) { throw new IOException("Package path traversal"); }
                    if (entry.isDirectory()) { Files.createDirectories(path); continue; }
                    if (!name.toLowerCase(Locale.ROOT).matches(".*\\.(json|png|jpg|jpeg|ttf|otf|txt|md)")) {
                        throw new IOException("Unsupported package file: " + name);
                    }
                    Files.createDirectories(path.getParent());
                    long size = 0;
                    try (var output = Files.newOutputStream(path, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                        final byte[] buffer = new byte[8192];
                        int length;
                        while ((length = zip.read(buffer)) != -1) {
                            size += length; total += length;
                            if (size > 24 * 1024 * 1024 || total > 64 * 1024 * 1024) {
                                throw new IOException("Skin package exceeds extraction limit");
                            }
                            output.write(buffer, 0, length);
                        }
                    }
                }
            }
            final MatchUiLayout layout = read(staging);
            final String name = layout.id() + "-" + UUID.randomUUID().toString().substring(0, 8);
            Files.move(staging, destination.resolve(name));
            installed = true;
            return name;
        } finally {
            if (!installed) {
                try (var files = Files.walk(staging)) {
                    for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.deleteIfExists(file); }
                }
            }
        }
    }
}
