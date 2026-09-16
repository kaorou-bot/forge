package forge.download;

import forge.util.UpdateManifest;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class CommunityUpdateManifestTest {
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    public void patchIsOptionalAndResolvesBesideFullInstaller() throws Exception {
        final Path manifestFile = Files.createTempFile("forge-update-manifest-", ".properties");
        try {
            Files.writeString(manifestFile, "schema=1\nversion=2.0.15-cn0916\n"
                    + "desktop.version=2.0.15-汉化-09.16\n"
                    + "desktop.url=desktop/2.0.15-cn0916/setup.exe\n"
                    + "desktop.size=100\ndesktop.sha256=" + SHA + "\n"
                    + "desktop.patch.from=2.0.15-汉化-09.15.2\n"
                    + "desktop.patch.url=desktop/2.0.15-cn0916/patch.exe\n"
                    + "desktop.patch.size=20\ndesktop.patch.sha256=" + SHA + "\n",
                    StandardCharsets.UTF_8);
            final UpdateManifest manifest = UpdateManifest.load(manifestFile.toUri().toString());
            Assert.assertEquals(manifest.desktop().version(), "2.0.15-汉化-09.16");
            Assert.assertEquals(manifest.desktopPatchFrom(), "2.0.15-汉化-09.15.2");
            Assert.assertTrue(manifest.desktopPatch().isPresent());
            Assert.assertTrue(manifest.resolveUrl(manifest.desktopPatch()).endsWith("/patch.exe"));

            Files.writeString(manifestFile, "schema=1\nversion=old\n"
                    + "desktop.url=old.zip\ndesktop.sha256=" + SHA + "\n", StandardCharsets.UTF_8);
            final UpdateManifest legacy = UpdateManifest.load(manifestFile.toUri().toString());
            Assert.assertFalse(legacy.desktopPatch().isPresent());
        } finally {
            Files.deleteIfExists(manifestFile);
        }
    }
}
