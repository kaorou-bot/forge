package forge.assets;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

import com.badlogic.gdx.files.FileHandle;
import forge.gui.GuiBase;
import forge.util.BuildInfo;
import forge.util.DateUtil;
import forge.util.ForgeUpdateConfig;
import forge.util.UpdateManifest;
import forge.util.AndroidStartupFiles;
import org.apache.commons.lang3.StringUtils;

import com.badlogic.gdx.Gdx;
import com.google.common.collect.ImmutableList;

import forge.Forge;
import forge.gui.FThreads;
import forge.gui.download.GuiDownloadZipService;
import forge.gui.util.SOptionPane;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.util.FileUtil;

import static forge.localinstance.properties.ForgeConstants.ASSETS_DIR;
import static forge.localinstance.properties.ForgeConstants.GITHUB_SNAPSHOT_URL;
import static forge.localinstance.properties.ForgeConstants.GITHUB_COMMITS_ATOM;
import static forge.localinstance.properties.ForgeConstants.GITHUB_FORGE_URL;
import static forge.localinstance.properties.ForgeConstants.GITHUB_RELEASES_ATOM;
import static forge.localinstance.properties.ForgeConstants.FONTS_DIR;
import static forge.localinstance.properties.ForgeConstants.LANG_DIR;
import static forge.localinstance.properties.ForgeConstants.RELEASE_URL;
import static forge.localinstance.properties.ForgeConstants.RES_DIR;
import static forge.localinstance.properties.ForgeConstants.USER_PREFS_DIR;

public class AssetsDownloader {
    private static ImmutableList<String> getDownloadIgnoreExitOptions() {
        return ImmutableList.of(Forge.getLocalizer().getMessage("lblDownload"), Forge.getLocalizer().getMessage("lblIgnore"), Forge.getLocalizer().getMessage("lblExit"));
    }

    private static ImmutableList<String> getDownloadExitOptions() {
        return ImmutableList.of(Forge.getLocalizer().getMessage("lblDownload"), Forge.getLocalizer().getMessage("lblExit"));
    }

    public static void checkForUpdates(boolean exited, Runnable runnable) {
        if (exited)
            return;
        if (GuiBase.isAndroid()) {
            Forge.getLocalizer().initialize(Forge.locale, LANG_DIR);
        }
        final String versionString = Forge.getDeviceAdapter().getVersionString();
        Forge.getSplashScreen().getProgressBar().setDescription(Forge.getLocalizer().getMessage("lblCheckingForUpdates"));
        if (versionString.contains("GIT")) {
            if (!GuiBase.isAndroid()) {
                run(runnable);
                return;
            }
        }

        final String packageSize = GuiBase.isAndroid() ? "160MB" : "270MB";
        final String apkSize = "12MB";

        final boolean isSnapshots = versionString.contains("SNAPSHOT");
        boolean connectedToInternet = Forge.getDeviceAdapter().isConnectedToInternet();
        UpdateManifest mirrorManifest = null;
        if (connectedToInternet) {
            if (!ForgeUpdateConfig.isMirrorEnabled()) {
                // This community localization uses its own mirror exclusively. Never fall back
                // to the upstream release or snapshot servers.
                connectedToInternet = false;
            } else {
                try {
                    mirrorManifest = UpdateManifest.load(ForgeUpdateConfig.getManifestUrl());
                } catch (Exception e) {
                    e.printStackTrace();
                    connectedToInternet = false;
                }
            }
        }
        final String snapsURL = GITHUB_SNAPSHOT_URL;
        // desktop and mobile-dev share the same package
        final String guiChannel = GuiBase.isAndroid() ? "forge/forge-gui-android/" : "forge/forge-gui-desktop/";
        final String releaseURL = RELEASE_URL +  guiChannel;
        // desktop and mobile-dev uses maven-metadata.xml on earlier releases
        final String versionText = isSnapshots ? snapsURL + "version.txt" : releaseURL + "maven-metadata.xml";
        FileHandle assetsDir = Gdx.files.absolute(ASSETS_DIR);
        FileHandle buildTxtFileHandle = GuiBase.isAndroid() ? Gdx.files.internal("build.txt") : Gdx.files.classpath("build.txt");
        final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        boolean verifyUpdatable = false;
        Date snapsTimestamp = null, buildTimeStamp = null;

        String message;
        if (connectedToInternet) {
            //currently for desktop/mobile-dev release on github
            final String releaseTag = mirrorManifest == null
                    ? Forge.getDeviceAdapter().getReleaseTag(GITHUB_RELEASES_ATOM)
                    : "forge-" + mirrorManifest.version();
            final UpdateManifest.Artifact mirrorInstaller = mirrorManifest == null ? null
                    : (GuiBase.isAndroid() ? mirrorManifest.android() : mirrorManifest.desktop());
            // A mirror manifest may intentionally publish only assets. In that case Android must
            // continue to the resource update below without trying to construct an empty APK URL.
            // Desktop has no separate resource package, so it can finish startup immediately.
            if (mirrorManifest != null && (mirrorInstaller == null || !mirrorInstaller.isPresent())) {
                if (!GuiBase.isAndroid()) {
                    run(runnable);
                    return;
                }
            } else {
            try {
                String version = mirrorManifest == null
                        ? (isSnapshots ? FileUtil.readFileToString(new URL(versionText)) : releaseTag.replace("forge-", ""))
                        : (mirrorInstaller.version().isEmpty() ? mirrorManifest.version() : mirrorInstaller.version());
                String filename = "";
                String installerURL = "";
                long installerSize = 0;
                String installerSha256 = "";
                if (GuiBase.isAndroid()) {
                    if (mirrorManifest != null) {
                        final UpdateManifest.Artifact artifact = mirrorManifest.android();
                        installerURL = mirrorManifest.resolveUrl(artifact);
                        filename = new FileHandle(new URL(installerURL).getPath()).name();
                        installerSize = artifact.size();
                        installerSha256 = artifact.sha256();
                    } else {
                        filename = "forge-android-" + version + "-signed-aligned.apk";
                        installerURL = isSnapshots ? snapsURL + filename : releaseURL + version + "/" + filename;
                    }
                } else {
                    if (mirrorManifest != null) {
                        final UpdateManifest.Artifact artifact = mirrorManifest.desktop();
                        installerURL = mirrorManifest.resolveUrl(artifact);
                        filename = new FileHandle(new URL(installerURL).getPath()).name();
                        installerSize = artifact.size();
                        installerSha256 = artifact.sha256();
                    } else {
                        //current release on github is tar.bz2, update this to jar installer in the future...
                        filename = isSnapshots ? "forge-installer-" + version + ".jar" : releaseTag.replace("forge-", "forge-gui-desktop-") + ".tar.bz2";
                        String releaseBZ2URL = GITHUB_FORGE_URL + "releases/download/" + releaseTag + "/" + filename;
                        String snapsBZ2URL = GITHUB_SNAPSHOT_URL + filename;
                        installerURL = isSnapshots ? snapsBZ2URL : releaseBZ2URL;
                    }
                }
                String snapsBuildDate = "", buildDate = "";
                if (mirrorManifest == null && isSnapshots) {
                    URL url = new URL(snapsURL + "build.txt");
                    snapsTimestamp = format.parse(FileUtil.readFileToString(url));
                    snapsBuildDate = snapsTimestamp.toString();
                    if (!GuiBase.isAndroid()) {
                        buildDate = BuildInfo.getTimestamp().toString();
                        verifyUpdatable = BuildInfo.verifyTimestamp(snapsTimestamp);
                    } else {
                        if (buildTxtFileHandle.exists()) {
                            buildTimeStamp = format.parse(buildTxtFileHandle.readString());
                            buildDate = buildTimeStamp.toString();
                            // if morethan 23 hours the difference, then allow to update..
                            verifyUpdatable = DateUtil.getElapsedHours(buildTimeStamp, snapsTimestamp) > 23;
                        } else {
                            //fallback to old version comparison
                            verifyUpdatable = !StringUtils.isEmpty(version) && !versionString.equals(version);
                        }
                    }
                } else {
                    verifyUpdatable = !StringUtils.isEmpty(version) && !versionString.equals(version);
                }

                if (verifyUpdatable) {
                    Forge.getSplashScreen().prepareForDialogs();

                    message = Forge.getLocalizer().getMessage("lblNewVersionForgeAvailableDetailed", version, snapsBuildDate, versionString, buildDate);
                    if (!Forge.getDeviceAdapter().isConnectedToWifi()) {
                        message += " " + Forge.getLocalizer().getMessage("lblConnectWifiForDownload", GuiBase.isAndroid() ? apkSize : packageSize);
                    }
                    if (mirrorManifest == null && isSnapshots) // this is for snaps initial info
                        message += Forge.getDeviceAdapter().getLatestChanges(GITHUB_COMMITS_ATOM, buildTimeStamp, snapsTimestamp);
                    //failed to grab latest github tag
                    if (!isSnapshots && releaseTag.isEmpty()) {
                        if (!GuiBase.isAndroid())
                            run(runnable);
                    } else if (SOptionPane.showConfirmDialog(message, Forge.getLocalizer().getMessage("lblNewVersionAvailable"), Forge.getLocalizer().getMessage("lblUpdateNow"), Forge.getLocalizer().getMessage("lblUpdateLater"), true, true)) {
                        String installer = new GuiDownloadZipService("", "update", installerURL,
                                Forge.getDeviceAdapter().getDownloadsDir(), null, Forge.getSplashScreen().getProgressBar(),
                                true, installerSize, installerSha256).download(filename);
                        if (installer != null) {
                            Forge.getDeviceAdapter().openFile(installer);
                            Forge.isMobileAdventureMode = Forge.advStartup;
                            Forge.exitAnimation(false);
                            return;
                        }
                        switch (SOptionPane.showOptionDialog(Forge.getLocalizer().getMessage("lblCouldNotDownloadUpdate"),
                                Forge.getLocalizer().getMessage("lblUpdateFailed"), null, ImmutableList.of(Forge.getLocalizer().getMessage("lblOK")))) {
                            default:
                                if (!GuiBase.isAndroid()) {
                                    run(runnable);
                                    return;
                                }
                                break;
                        }
                    }
                } else {
                    if (!GuiBase.isAndroid()) {
                        run(runnable);
                        return;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                if (!GuiBase.isAndroid()) {
                    run(runnable);
                    return;
                }
            }
            }
        } else {
            if (!GuiBase.isAndroid()) {
                run(runnable);
                return;
            }
        }
        // non android don't have seperate package to check
        if (!GuiBase.isAndroid()) {
            run(runnable);
            return;
        }
        // Installed resources are usable offline independently of the APK version,
        // optional adventure artwork and the in-memory skin list.
        final boolean localResourcesUsable = AndroidStartupFiles.hasUsableResources(Paths.get(ASSETS_DIR));
        if (!connectedToInternet && localResourcesUsable) {
            System.out.println("[startup] Using local resources without an update connection");
            run(runnable);
            return;
        }
        FileHandle versionFile = assetsDir.child("version.txt");
        final UpdateManifest.Artifact mirrorAssets = mirrorManifest == null ? null : mirrorManifest.assets();
        final String resourceVersion = mirrorAssets != null && mirrorAssets.isPresent()
                ? mirrorAssets.version() : "";
        if (localResourcesUsable && (resourceVersion.isEmpty()
                || resourceVersion.equals(FileUtil.readFileToString(versionFile.file())))) {
            run(runnable);
            return;
        }

        Forge.getSplashScreen().prepareForDialogs(); //ensure colors set up for showing message dialogs

        boolean canIgnoreDownload = localResourcesUsable;

        if (!connectedToInternet) {
            message = Forge.getLocalizer().getMessage("lblUpdatedResourcesUnavailable") + "\n\n";
            if (canIgnoreDownload) {
                message += Forge.getLocalizer().getMessage("lblContinueWithoutResourceUpdate");
            } else {
                message += Forge.getLocalizer().getMessage("lblCannotStartWithoutResources");
            }
            switch (SOptionPane.showOptionDialog(message, Forge.getLocalizer().getMessage("lblNoInternetConnection"), null, ImmutableList.of(Forge.getLocalizer().getMessage("lblOK")))) {
                default: {
                    if (!canIgnoreDownload) {
                        Forge.isMobileAdventureMode = Forge.advStartup;
                        Forge.exitAnimation(false); //exit if can't ignore download
                    } else {
                        // A failed connectivity or manifest check must not block startup when
                        // a complete, previously installed resource set is available locally.
                        run(runnable);
                    }
                }
            }
            return;
        }

        //prompt user whether they wish to download the updated resource files
        message = Forge.getLocalizer().getMessage("lblUpdatedResourcesDownload", packageSize) + " ";
        if (Forge.getDeviceAdapter().isConnectedToWifi()) {
            message += Forge.getLocalizer().getMessage("lblWifiDownloadShouldBeQuick");
        } else {
            message += Forge.getLocalizer().getMessage("lblWifiDownloadRecommended");
        }
        final List<String> options;
        message += "\n\n";
        if (canIgnoreDownload) {
            message += Forge.getLocalizer().getMessage("lblIgnoreResourceUpdateWarning");
            options = getDownloadIgnoreExitOptions();
        } else {
            message += Forge.getLocalizer().getMessage("lblResourceUpdateMandatory");
            options = getDownloadExitOptions();
        }

        switch (SOptionPane.showOptionDialog(message, "", null, options)) {
            case 1:
                if (!canIgnoreDownload) {
                    Forge.isMobileAdventureMode = Forge.advStartup;
                    Forge.exitAnimation(false); //exit if can't ignore download
                    return;
                } else {
                    run(runnable);
                    return;
                }
            case 2:
                Forge.isMobileAdventureMode = Forge.advStartup;
                Forge.exitAnimation(false);
                return;
        }

        //allow deletion on Android 10 or if using app-specific directory
        boolean allowDeletion = Forge.androidVersion < 30 || GuiBase.isUsingAppDirectory();
        final String assetURL;
        final long assetSize;
        final String assetSha256;
        if (mirrorAssets != null && mirrorAssets.isPresent()) {
            try {
                assetURL = mirrorManifest.resolveUrl(mirrorAssets);
            } catch (Exception e) {
                e.printStackTrace();
                Forge.isMobileAdventureMode = Forge.advStartup;
                Forge.exitAnimation(false);
                return;
            }
            assetSize = mirrorAssets.size();
            assetSha256 = mirrorAssets.sha256();
        } else {
            assetURL = isSnapshots ? snapsURL + "assets.zip" : releaseURL + versionString + "/" + "assets.zip";
            assetSize = 0;
            assetSha256 = "";
        }
        GuiDownloadZipService downloader = new GuiDownloadZipService("", Forge.getLocalizer().getMessage("lblResourceFiles"), assetURL,
                ASSETS_DIR, RES_DIR, Forge.getSplashScreen().getProgressBar(), allowDeletion,
                assetSize, assetSha256);
        String archive = downloader.download("temp.zip");
        boolean installed = false;
        if (archive != null) {
            try {
                // Written only after a verified download, before any old resource is touched.
                writeAtomically(assetsDir.child(AndroidStartupFiles.INSTALLING), resourceVersion);
                downloader.extract(archive);
                if (downloader.wasExtractionSuccessful() && AndroidStartupFiles.hasCoreResources(Paths.get(ASSETS_DIR))) {
                    writeAtomically(versionFile, resourceVersion);
                    Files.delete(Paths.get(ASSETS_DIR, AndroidStartupFiles.INSTALLING));
                    installed = true;
                }
            } catch (IOException e) { e.printStackTrace(); }
        }
        if (!installed) {
            SOptionPane.showOptionDialog(Forge.getLocalizer().getMessage("lblCouldNotDownloadUpdate"),
                    Forge.getLocalizer().getMessage("lblUpdateFailed"), null,
                    ImmutableList.of(Forge.getLocalizer().getMessage("lblOK")));
            if (AndroidStartupFiles.hasUsableResources(Paths.get(ASSETS_DIR))) {
                run(runnable);
            } else {
                Forge.isMobileAdventureMode = Forge.advStartup;
                Forge.exitAnimation(false);
            }
            return;
        }

        if (allowDeletion)
            FSkinFont.deleteCachedFiles(); //delete cached font files in case any skin's .ttf file changed

        //reload light version of skin after assets updated
        FThreads.invokeInEdtAndWait(() -> {
            FSkinFont.updateAll(); //update all fonts used by splash screen
            FSkin.loadLight(FSkin.getName(), Forge.getSplashScreen());
        });

        // auto restart after update
        Forge.isMobileAdventureMode = Forge.advStartup;
        Forge.exitAnimation(true);
    }

    private static void run(Runnable toRun) {
        if (toRun != null) {
            if (!GuiBase.isAndroid()) {
                Forge.getSplashScreen().getProgressBar().setDescription(Forge.getLocalizer().getMessage("lblLoadingGameResources"));
            }
            FThreads.invokeInBackgroundThread(toRun);
            return;
        }
        if (!GuiBase.isAndroid()) {
            Forge.isMobileAdventureMode = Forge.advStartup;
            Forge.exitAnimation(false);
        }
    }

    private static void installBundledLocalizationOverrides() {
        if (!GuiBase.isAndroid()) {
            return;
        }
        FileHandle destination = Gdx.files.absolute(LANG_DIR);
        destination.mkdirs();
        for (String fileName : ImmutableList.of("en-US.properties", "zh-CN.properties", "cardnames-zh-CN.txt")) {
            FileHandle bundledFile = Gdx.files.internal("localization/" + fileName);
            if (bundledFile.exists()) {
                installAtomically(bundledFile, destination.child(fileName));
            }
        }
    }

    private static boolean installBundledCjkFont() {
        if (!GuiBase.isAndroid()) {
            return false;
        }
        final String fontName = "SourceHanSansCN";
        FileHandle bundledFont = Gdx.files.internal("bundled-font/" + fontName + ".ttf");
        if (!bundledFont.exists()) {
            return false;
        }
        boolean fontConfigurationChanged = false;
        FileHandle fontDirectory = Gdx.files.absolute(FONTS_DIR);
        fontDirectory.mkdirs();
        FileHandle installedFont = fontDirectory.child(fontName + ".ttf");
        // v2 switches CJK rendering from persisted bitmap caches to incremental FreeType glyphs.
        // Its one-time marker forces old installations to discard every stale .fnt/.png atlas.
        FileHandle installMarker = fontDirectory.child(".bundled-cjk-v2-" + bundledFont.length() + ".ready");
        if (!installMarker.exists()) {
            fontConfigurationChanged = true;
        }
        if (installAtomically(bundledFont, installedFont)) {
            fontConfigurationChanged = true;
        }
        FileHandle bundledLicense = Gdx.files.internal("bundled-font/OFL.txt");
        if (bundledLicense.exists()) {
            installAtomically(bundledLicense, fontDirectory.child("SourceHanSansCN-OFL.txt"));
        }
        if (FModel.getPreferences().getPref(FPref.UI_CJK_FONT).isEmpty()) {
            FileUtil.ensureDirectoryExists(USER_PREFS_DIR);
            FModel.getPreferences().setPref(FPref.UI_CJK_FONT, fontName);
            FModel.getPreferences().save();
            Forge.CJK_Font = fontName;
            fontConfigurationChanged = true;
        }
        if (fontConfigurationChanged) {
            for (FileHandle marker : fontDirectory.list(".ready")) {
                if (marker.name().startsWith(".bundled-cjk-") && !marker.equals(installMarker)) {
                    marker.delete();
                }
            }
            try { writeAtomically(installMarker, "SourceHanSansCN " + bundledFont.length()); }
            catch (IOException e) { e.printStackTrace(); }
        }
        return fontConfigurationChanged;
    }

    /** Called on the GL thread before loading the splash skin, not during rendering. */
    public static void prepareBundledFiles() {
        installBundledLocalizationOverrides();
        if (installBundledCjkFont()) {
            // No live font/texture objects yet: do not instantiate Assets here.
            FSkinFont.deleteCachedFiles();
        }
    }

    private static boolean installAtomically(FileHandle source, FileHandle target) {
        try { return AndroidStartupFiles.install(source::read, target.file().toPath()); }
        catch (IOException e) {
            System.err.println("[startup] Could not install " + target + ": " + e);
            if (!target.exists() || target.length() == 0) { throw new IllegalStateException("Missing startup file: " + target, e); }
            return false; // Keep the previous complete file when the filesystem is unavailable/full.
        }
    }

    private static void writeAtomically(FileHandle target, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        AndroidStartupFiles.install(() -> new ByteArrayInputStream(bytes), target.file().toPath());
    }
}
