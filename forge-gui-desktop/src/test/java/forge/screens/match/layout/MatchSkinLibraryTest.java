package forge.screens.match.layout;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.testng.Assert;
import org.testng.annotations.Test;

public class MatchSkinLibraryTest {
    private static final String JSON = """
            {"version":3,"id":"saved-example","regions":[{"bounds":[0,.1,1,.9],"documents":["remaining"]}],
             "scene":{"widgets":{"PHASES_ACTIVE":[0,0,1,.1]}}}
            """;

    @Test public void importsPersistAsIndependentChoicesAndRemoveOnlyChosenCopy() throws Exception {
        final Path temp = Files.createTempDirectory("forge-skin-library-");
        try {
            final Path archive = zip(temp.resolve("original.zip"));
            final byte[] original = Files.readAllBytes(archive);
            final Path library = temp.resolve("saved");
            final String first = MatchSkinPackages.install(archive, library);
            final String second = MatchSkinPackages.install(archive, library);
            Assert.assertNotEquals(first, second);
            Assert.assertEquals(MatchSkinPackages.installed(library).size(), 2);
            // Listing anew is disk-backed, without any in-memory registration or the source ZIP.
            Files.move(archive, temp.resolve("original-moved.zip"));
            Assert.assertTrue(MatchSkinPackages.installed(library).contains(first));
            Assert.assertTrue(Files.exists(library.resolve(first).resolve("images/readme.txt")));
            MatchSkinPackages.remove(first, library);
            Assert.assertFalse(Files.exists(library.resolve(first)));
            Assert.assertEquals(MatchSkinPackages.installed(library), List.of(second));
            Assert.assertEquals(Files.readAllBytes(temp.resolve("original-moved.zip")), original);
            Assert.assertEquals(Files.readString(library.resolve(second).resolve("match-ui.json")), JSON);
            MatchSkinPackages.remove(second, library);
            Assert.assertEquals(MatchSkinPackages.installed(library), List.of());
            Assert.assertTrue(Files.isDirectory(library), "The library root must never be removed");
        } finally { cleanup(temp); }
    }

    @Test public void removalRejectsTraversalMissingPackagesAndUnrelatedDirectories() throws Exception {
        final Path temp = Files.createTempDirectory("forge-skin-scope-");
        try {
            final Path library = Files.createDirectory(temp.resolve("saved"));
            Files.writeString(temp.resolve("keep.txt"), "outside");
            Files.createDirectory(library.resolve("unrelated"));
            Files.writeString(library.resolve("unrelated/keep.txt"), "inside but not a skin");
            for (String name : List.of("", ".", "..", "../keep.txt", "a/../b", "C:/", "a\\b", "missing", "unrelated", "dusk-sanctum")) {
                Assert.expectThrows(IOException.class, () -> MatchSkinPackages.remove(name, library));
            }
            Assert.expectThrows(IOException.class, () -> MatchSkinPackages.remove(null, library));
            Assert.assertEquals(Files.readString(temp.resolve("keep.txt")), "outside");
            Assert.assertTrue(Files.exists(library.resolve("unrelated/keep.txt")));
            Assert.assertTrue(MatchSkinPackages.installed(library).isEmpty());
        } finally { cleanup(temp); }
    }

    @Test public void linkedPackagesAndNestedSymlinksAreRejectedBeforeAnyDeletion() throws Exception {
        final Path temp = Files.createTempDirectory("forge-skin-links-").toRealPath();
        try {
            final Path library = Files.createDirectory(temp.resolve("saved"));
            final Path outside = Files.createDirectory(temp.resolve("outside"));
            Files.writeString(outside.resolve("match-ui.json"), JSON);
            Files.writeString(outside.resolve("keep.txt"), "never touch");
            Files.createSymbolicLink(library.resolve("alias"), outside);
            Assert.assertTrue(MatchSkinPackages.installed(library).isEmpty());
            Assert.expectThrows(IOException.class, () -> MatchSkinPackages.remove("alias", library));
            final String name = MatchSkinPackages.install(zip(temp.resolve("original.zip")), library);
            final Path base = library.resolve(name);
            Files.createSymbolicLink(base.resolve("nested"), outside);
            Assert.expectThrows(IOException.class, () -> MatchSkinPackages.remove(name, library));
            Assert.assertTrue(Files.exists(base.resolve("images/readme.txt")), "Preflight must finish before any file is deleted");
            Assert.assertEquals(Files.readString(outside.resolve("keep.txt")), "never touch");
        } finally { cleanup(temp); }
    }

    @Test public void forgettingActiveOrRememberedSkinPersistsFallbackWithoutChangingOtherChoice() throws Exception {
        final Path temp = Files.createTempDirectory("forge-skin-selection-");
        final var selection = DesktopMatchUi.class.getDeclaredField("selection");
        final var packageName = DesktopMatchUi.class.getDeclaredField("packageName");
        selection.setAccessible(true); packageName.setAccessible(true);
        final Object previousSelection = selection.get(null), previousPackage = packageName.get(null);
        try {
            final Path file = temp.resolve("settings.properties");
            for (String provider : List.of("package", "classic", "dusk-sanctum")) {
                selection.set(null, provider); packageName.set(null, "saved-example");
                DesktopMatchUi.saveSelection(file, provider, "saved-example");
                final byte[] unchanged = Files.readAllBytes(file);
                Assert.assertFalse(DesktopMatchUi.forgetPackage("another-skin", file));
                Assert.assertEquals(Files.readAllBytes(file), unchanged);
                Assert.assertEquals(DesktopMatchUi.forgetPackage("saved-example", file), provider.equals("package"));
                Assert.assertEquals(DesktopMatchUi.selection(), provider.equals("package") ? "classic" : provider);
                Assert.assertNull(DesktopMatchUi.selectedPackage());
                final var settings = new Properties();
                try (var reader = Files.newBufferedReader(file)) { settings.load(reader); }
                Assert.assertEquals(settings.getProperty("provider"), DesktopMatchUi.selection());
                Assert.assertNull(settings.getProperty("package"), "Restart must not refer to the removed package");
            }
            try (var entries = Files.list(temp)) { Assert.assertEquals(entries.count(), 1L, "No partial settings left behind"); }
        } finally {
            selection.set(null, previousSelection); packageName.set(null, previousPackage);
            cleanup(temp);
        }
    }

    @Test public void failedPreferenceWritePreservesSelectionAndCleansTemporaryFile() throws Exception {
        final Path temp = Files.createTempDirectory("forge-skin-write-failure-");
        final var selection = DesktopMatchUi.class.getDeclaredField("selection");
        final var packageName = DesktopMatchUi.class.getDeclaredField("packageName");
        selection.setAccessible(true); packageName.setAccessible(true);
        final Object previousSelection = selection.get(null), previousPackage = packageName.get(null);
        try {
            selection.set(null, "package"); packageName.set(null, "saved-example");
            final Path blocked = Files.createDirectory(temp.resolve("settings.properties"));
            Files.writeString(blocked.resolve("keep.txt"), "keep");
            Assert.expectThrows(IOException.class, () -> DesktopMatchUi.forgetPackage("saved-example", blocked));
            Assert.assertEquals(DesktopMatchUi.selection(), "package");
            Assert.assertEquals(DesktopMatchUi.selectedPackage(), "saved-example");
            Assert.assertEquals(Files.readString(blocked.resolve("keep.txt")), "keep");
            try (var entries = Files.list(temp)) { Assert.assertEquals(entries.count(), 1L); }
        } finally {
            selection.set(null, previousSelection); packageName.set(null, previousPackage);
            cleanup(temp);
        }
    }

    private static Path zip(Path archive) throws IOException {
        try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("match-ui.json")); zip.write(JSON.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("images/readme.txt")); zip.write("retained image directory".getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
        }
        return archive;
    }
    private static void cleanup(Path temp) throws IOException {
        // All paths originate in this test's dedicated temp directory; walk does not follow symlinks.
        try (var paths = Files.walk(temp)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
        }
    }
}
