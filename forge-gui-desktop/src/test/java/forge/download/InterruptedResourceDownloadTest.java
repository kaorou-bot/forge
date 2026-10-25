package forge.download;

import forge.download.UpdateManifestTransportTest.LocalServer;
import forge.gui.GuiBase;
import forge.gui.download.GuiDownloadZipService;
import forge.gui.interfaces.IGuiBase;
import forge.gui.interfaces.IProgressBar;
import forge.util.AndroidStartupFiles;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class InterruptedResourceDownloadTest {
    private static final byte[] OLD = "previous verified package".getBytes(StandardCharsets.UTF_8);
    private Path root;
    private IGuiBase previous;
    private byte[] payload;
    private final List<Boolean> sleepCalls = new ArrayList<>();

    @BeforeMethod public void setUp() throws Exception {
        Path output = Path.of(System.getProperty("forge.download.testDir", "target/interrupted-download-tests"));
        Files.createDirectories(output);
        root = Files.createTempDirectory(output, "case-");
        sleepCalls.clear();
        previous = GuiBase.getInterface();
        GuiBase.setInterface((IGuiBase) Proxy.newProxyInstance(IGuiBase.class.getClassLoader(),
                new Class<?>[] {IGuiBase.class}, (proxy, method, args) -> {
                    if (method.getName().equals("preventSystemSleep")) {
                        sleepCalls.add((Boolean) args[0]);
                    }
                    return null;
                }));
        byte[] random = new byte[16384];
        new Random(31).nextBytes(random);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("res/not-extracted.txt"));
            zip.write(random);
            zip.closeEntry();
        }
        payload = bytes.toByteArray();
    }

    @AfterMethod(alwaysRun = true) public void restoreGui() {
        GuiBase.setInterface(previous);
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private GuiDownloadZipService service(String url, RecordingProgress progress, long size, String hash) {
        return new GuiDownloadZipService("test", "resources", url, root.toString(),
                root.resolve("res").toString(), progress, true, size, hash);
    }

    private void installedResources() throws Exception {
        for (String file : new String[] {"languages/en-US.properties", "skins/default/bg_splash.png",
                "skins/default/sprite_icons.png", "skins/default/font1.ttf", "editions/Test.txt", "tokenscripts/test.txt"}) {
            Path target = root.resolve("res/" + file);
            Files.createDirectories(target.getParent());
            Files.writeString(target, "existing resource");
        }
        Path cards = root.resolve("res/cardsfolder/cardsfolder.zip");
        Files.createDirectories(cards.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(cards))) {
            zip.putNextEntry(new ZipEntry("a/test.txt"));
            zip.write("Name:Test".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Files.write(root.resolve("temp.zip"), OLD);
        Assert.assertTrue(AndroidStartupFiles.hasUsableResources(root));
    }

    private void failedDownloadPreservedInstallation() throws Exception {
        Assert.assertEquals(Files.readAllBytes(root.resolve("temp.zip")), OLD);
        Assert.assertFalse(Files.exists(root.resolve("temp.zip.part")));
        Assert.assertFalse(Files.exists(root.resolve(AndroidStartupFiles.INSTALLING)));
        Assert.assertTrue(AndroidStartupFiles.hasUsableResources(root));
        Assert.assertEquals(Files.readString(root.resolve("res/languages/en-US.properties")), "existing resource");
        Assert.assertEquals(sleepCalls, List.of(true, false));
    }

    @Test public void truncatedDownloadKeepsOldPackageAndResources() throws Exception {
        installedResources();
        try (LocalServer server = new LocalServer(e -> {
            e.sendResponseHeaders(200, payload.length);
            e.getResponseBody().write(payload, 0, payload.length / 2);
        })) {
            Assert.assertNull(service(server.url(), new RecordingProgress(), payload.length, sha(payload)).download("temp.zip"));
        }
        failedDownloadPreservedInstallation();
    }

    @Test public void cancellationAfterReceivingDataKeepsOldPackage() throws Exception {
        installedResources();
        try (LocalServer server = new LocalServer(e -> UpdateManifestTransportTest.respond(e, 200, payload, false))) {
            RecordingProgress progress = new RecordingProgress();
            GuiDownloadZipService downloader = service(server.url(), progress, payload.length, sha(payload));
            progress.onValue = () -> downloader.setCancel(true);
            Assert.assertNull(downloader.download("temp.zip"));
            Assert.assertFalse(progress.values.isEmpty(), "Cancellation must occur after data arrives");
        }
        failedDownloadPreservedInstallation();
    }

    @Test public void wrongHashKeepsOldPackage() throws Exception {
        installedResources();
        try (LocalServer server = new LocalServer(e -> UpdateManifestTransportTest.respond(e, 200, payload, false))) {
            Assert.assertNull(service(server.url(), new RecordingProgress(), payload.length, "0".repeat(64)).download("temp.zip"));
        }
        failedDownloadPreservedInstallation();
    }

    @Test public void wrongExpectedSizeKeepsOldPackage() throws Exception {
        installedResources();
        try (LocalServer server = new LocalServer(e -> UpdateManifestTransportTest.respond(e, 200, payload, false))) {
            Assert.assertNull(service(server.url(), new RecordingProgress(), payload.length + 1, sha(payload)).download("temp.zip"));
        }
        failedDownloadPreservedInstallation();
    }

    @Test public void httpFailureAndEmptyResponseDoNotReplaceOldPackage() throws Exception {
        installedResources();
        for (int status : new int[] {503, 200}) {
            sleepCalls.clear();
            try (LocalServer server = new LocalServer(e -> UpdateManifestTransportTest.respond(e, status, new byte[0], false))) {
                Assert.assertNull(service(server.url(), new RecordingProgress(), payload.length, sha(payload)).download("temp.zip"));
            }
            failedDownloadPreservedInstallation();
        }
    }

    @Test public void stalePartialIsReplacedByVerifiedDownloadWithoutExtracting() throws Exception {
        installedResources();
        Files.writeString(root.resolve("temp.zip.part"), "interrupted bytes must not be appended");
        try (LocalServer server = new LocalServer(e -> UpdateManifestTransportTest.respond(e, 200, payload, false))) {
            Assert.assertEquals(service(server.url(), new RecordingProgress(), payload.length, sha(payload)).download("temp.zip"),
                    root.resolve("temp.zip").toString());
        }
        Assert.assertEquals(Files.readAllBytes(root.resolve("temp.zip")), payload);
        Assert.assertFalse(Files.exists(root.resolve("temp.zip.part")));
        Assert.assertFalse(Files.exists(root.resolve("res/not-extracted.txt")));
        Assert.assertFalse(Files.exists(root.resolve(AndroidStartupFiles.INSTALLING)));
        Assert.assertTrue(AndroidStartupFiles.hasUsableResources(root));
        Assert.assertEquals(sleepCalls, List.of(true, false));
    }

    @Test public void unknownContentLengthWorksWithAndWithoutManifestSize() throws Exception {
        for (long size : new long[] {payload.length, 0}) {
            sleepCalls.clear();
            RecordingProgress progress = new RecordingProgress();
            try (LocalServer server = new LocalServer(e -> UpdateManifestTransportTest.respond(e, 200, payload, true))) {
                Assert.assertNotNull(service(server.url(), progress, size, sha(payload)).download("temp.zip"));
            }
            Assert.assertEquals(Files.readAllBytes(root.resolve("temp.zip")), payload);
            Assert.assertTrue(progress.values.stream().allMatch(value -> value >= 0 && value <= 100));
            Assert.assertEquals(progress.values.isEmpty(), size == 0);
            Assert.assertFalse(Files.exists(root.resolve("temp.zip.part")));
            Assert.assertFalse(Files.exists(root.resolve(AndroidStartupFiles.INSTALLING)));
            Assert.assertFalse(AndroidStartupFiles.hasUsableResources(root), "Download alone must not install resources");
            Assert.assertEquals(sleepCalls, List.of(true, false));
        }
    }

    @Test public void leftoverDownloadsDoNotChangeResourceUsability() throws Exception {
        Files.write(root.resolve("temp.zip"), OLD);
        Files.writeString(root.resolve("temp.zip.part"), "incomplete");
        Assert.assertFalse(AndroidStartupFiles.hasUsableResources(root));
        installedResources();
        Assert.assertTrue(AndroidStartupFiles.hasUsableResources(root));
        Assert.assertFalse(Files.exists(root.resolve(AndroidStartupFiles.INSTALLING)));
    }

    @Test public void stalledDownloadTimesOutAndPreservesOldInstallation() throws Exception {
        installedResources();
        CountDownLatch release = new CountDownLatch(1);
        try (LocalServer server = new LocalServer(e -> {
            e.sendResponseHeaders(200, payload.length);
            e.getResponseBody().write(payload, 0, 1);
            e.getResponseBody().flush();
            UpdateManifestTransportTest.awaitRelease(release);
        })) {
            try {
                long started = System.nanoTime();
                Assert.assertNull(service(server.url(), new RecordingProgress(), payload.length, sha(payload)).download("temp.zip"));
                long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                Assert.assertTrue(elapsed >= 29000 && elapsed < 45000, "Read timeout took " + elapsed + " ms");
            } finally {
                release.countDown();
            }
        }
        failedDownloadPreservedInstallation();
    }

    private static final class RecordingProgress implements IProgressBar {
        private final List<Integer> values = new ArrayList<>();
        private int maximum;
        private Runnable onValue;
        @Override public void setDescription(String description) { }
        @Override public void setValue(int value) {
            values.add(value);
            if (onValue != null) { onValue.run(); }
        }
        @Override public void reset() { }
        @Override public void setShowETA(boolean value) { }
        @Override public void setShowCount(boolean value) { }
        @Override public void setPercentMode(boolean value) { }
        @Override public int getMaximum() { return maximum; }
        @Override public void setMaximum(int value) { maximum = value; }
    }
}
