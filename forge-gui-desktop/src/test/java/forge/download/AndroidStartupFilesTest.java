package forge.download;

import forge.util.AndroidStartupFiles;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.gui.download.GuiDownloadZipService;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class AndroidStartupFilesTest {
    private Path completeResources() throws IOException {
        Path root = Files.createTempDirectory("forge-startup-test-");
        for (String file : new String[] {"languages/en-US.properties", "skins/default/bg_splash.png",
                "skins/default/sprite_icons.png", "skins/default/font1.ttf", "editions/Test.txt", "tokenscripts/test.txt"}) {
            Path path = root.resolve("res/" + file);
            Files.createDirectories(path.getParent());
            Files.writeString(path, "fixture");
        }
        Path cards = root.resolve("res/cardsfolder/cardsfolder.zip");
        Files.createDirectories(cards.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(cards))) {
            zip.putNextEntry(new ZipEntry("a/test_card.txt"));
            zip.write("Name:Test Card".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return root;
    }

    private void clean(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) { Files.deleteIfExists(p); }
        }
    }

    @Test public void offlineResourcesDoNotRequireVersionOrSkinCache() throws Exception {
        Path root = completeResources();
        try {
            Assert.assertFalse(Files.exists(root.resolve("version.txt")));
            Assert.assertTrue(AndroidStartupFiles.hasUsableResources(root));
            Files.writeString(root.resolve("version.txt"), "2.0.15-cn.assets.20260922");
            Assert.assertTrue(AndroidStartupFiles.hasUsableResources(root));
            Files.writeString(root.resolve("version.txt"), "");
            Assert.assertTrue(AndroidStartupFiles.hasUsableResources(root));
        } finally { clean(root); }
    }

    @Test public void firstInstallCannotStartWithoutResources() throws Exception {
        Path root = Files.createTempDirectory("forge-startup-test-");
        try { Assert.assertFalse(AndroidStartupFiles.hasUsableResources(root)); }
        finally { clean(root); }
    }

    @Test public void interruptedExtractionIsNotACompleteInstall() throws Exception {
        Path root = completeResources();
        try {
            Files.writeString(root.resolve(AndroidStartupFiles.INSTALLING), "pending");
            Assert.assertTrue(AndroidStartupFiles.hasCoreResources(root));
            Assert.assertFalse(AndroidStartupFiles.hasUsableResources(root));
            Files.delete(root.resolve(AndroidStartupFiles.INSTALLING));
            Assert.assertTrue(AndroidStartupFiles.hasUsableResources(root));
        } finally { clean(root); }
    }

    @Test public void brokenCardArchiveCannotStart() throws Exception {
        Path root = completeResources();
        try {
            Files.writeString(root.resolve("res/cardsfolder/cardsfolder.zip"), "truncated");
            Assert.assertFalse(AndroidStartupFiles.hasUsableResources(root));
        } finally { clean(root); }
    }

    @Test public void missingCoreFileCannotStart() throws Exception {
        Path root = completeResources();
        try {
            Files.delete(root.resolve("res/skins/default/font1.ttf"));
            Assert.assertFalse(AndroidStartupFiles.hasUsableResources(root));
        } finally { clean(root); }
    }

    @Test public void sameContentDoesNotRewriteAndSameSizeCorruptionIsRepaired() throws Exception {
        Path root = Files.createTempDirectory("forge-startup-test-");
        try {
            Path target = root.resolve("zh-CN.txt");
            byte[] bytes = "new".getBytes(StandardCharsets.UTF_8);
            Files.write(target, bytes);
            Files.setLastModifiedTime(target, FileTime.fromMillis(1000));
            Assert.assertFalse(AndroidStartupFiles.install(() -> new ByteArrayInputStream(bytes), target));
            Assert.assertEquals(Files.getLastModifiedTime(target).toMillis(), 1000L);
            Files.writeString(target, "bad");
            Assert.assertTrue(AndroidStartupFiles.install(() -> new ByteArrayInputStream(bytes), target));
            Assert.assertEquals(Files.readAllBytes(target), bytes);
        } finally { clean(root); }
    }

    @Test public void killedCopyLeavesPreviousLanguageFileIntact() throws Exception {
        Path root = Files.createTempDirectory("forge-startup-test-");
        try {
            Path target = root.resolve("cardnames-zh-CN.txt");
            Files.writeString(target, "previous complete translation");
            AtomicInteger opened = new AtomicInteger();
            AndroidStartupFiles.InputSource source = () -> {
                if (opened.incrementAndGet() == 1) { return new ByteArrayInputStream(new byte[] {1, 2}); }
                return new InputStream() {
                    private boolean first = true;
                    @Override public int read() throws IOException {
                        if (first) { first = false; return 1; }
                        throw new IOException("simulated interrupted copy");
                    }
                };
            };
            Assert.expectThrows(IOException.class, () -> AndroidStartupFiles.install(source, target));
            Assert.assertEquals(Files.readString(target), "previous complete translation");
            try (var files = Files.list(root)) { Assert.assertEquals(files.count(), 1L); }
        } finally { clean(root); }
    }

    @Test public void staleCopyFromKilledProcessDoesNotBlockNextStart() throws Exception {
        Path root = Files.createTempDirectory("forge-startup-test-");
        try {
            Path target = root.resolve("font.ttf");
            Files.writeString(root.resolve("font.ttf.old.tmp"), "partial");
            Assert.assertTrue(AndroidStartupFiles.install(() -> new ByteArrayInputStream(new byte[] {1, 2}), target));
            Assert.assertEquals(Files.readAllBytes(target), new byte[] {1, 2});
        } finally { clean(root); }
    }

    @Test public void extractionFailureIsNotReportedAsInstalled() throws Exception {
        Path root = Files.createTempDirectory("forge-extraction-test-");
        IGuiBase previous = GuiBase.getInterface();
        try {
            GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),
                    new Class<?>[] {IGuiBase.class}, (proxy, method, args) -> null));
            Path archive = root.resolve("resource.zip");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
                zip.putNextEntry(new ZipEntry("file.txt"));
                zip.write(new byte[] {1, 2, 3});
                zip.closeEntry();
            }
            GuiDownloadZipService broken = new GuiDownloadZipService("test", "test", "", root.resolve("out").toString(), null, null) {
                @Override protected void copyInputStream(InputStream input, String path) throws IOException {
                    input.close();
                    throw new IOException("simulated full disk");
                }
            };
            broken.extract(archive.toString());
            Assert.assertFalse(broken.wasExtractionSuccessful());
            Assert.assertTrue(Files.exists(archive));
            GuiDownloadZipService valid = new GuiDownloadZipService("test", "test", "", root.resolve("out").toString(), null, null);
            valid.extract(archive.toString());
            Assert.assertTrue(valid.wasExtractionSuccessful());
            Assert.assertFalse(Files.exists(archive));
            Assert.assertEquals(Files.readAllBytes(root.resolve("out/file.txt")), new byte[] {1, 2, 3});
        } finally { GuiBase.setInterface(previous); clean(root); }
    }
}
