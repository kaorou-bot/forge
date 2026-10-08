For community releases use `deploy/build-android-community.ps1`, with versions from the root `pom.xml` and the existing signing keystore. See [community maintenance](Community-ZH-CN-Maintenance.md). The historical upstream instructions below are not the community release workflow.

## Community APK resource gate (2026-10-08)

D8 packages Java code, not dependency resources. `package-android-classpath-resources.ps1` copies libGDX GLSL files from the **resolved runtime JARs** to the APK root, where Android `FileHandle.classpath` reads them. Missing default/depth/particle vertex or fragment shaders abort the build. Do not put these files only under `assets/`, or hard-code a libGDX version.

The Android POM also bundles five coin/dice textures under `assets/match-animation/`. Startup atomically installs missing textures into `ForgeConstants.RES_DIR/skins/default/`, retaining existing artwork and tolerating optional texture installation failure. This supports old extracted resource packs without another full resource download.

Run `deploy/test-android-classpath-resources.ps1 -JavaHome <jdk> -GdxJar <resolved-gdx.jar>`: it compares all six shader entries byte-for-byte and checks rejection of missing dependencies. The main build checks the final signed APK for all six shaders and five textures, signatures, alignment and version metadata. Build/sign/inspect inside the ASCII mapped staging path; copy to the central release directory only after validation. Old `aapt` cannot reliably inspect a central path containing Chinese characters.

At runtime, test coin toss **after ante confirmation**, not just APK startup: shader compilation happens on first render. Also test dice/coin render and disposal failures with `MatchAnimationFailureTest`, and unavailable-font sizing with `FontHeightSelectionTest`. Keep raw test logs and screenshots under ignored `dist/diagnostics/`; they are not release artifacts.

For lifecycle regressions, also exit through the application's own confirmation dialog and reopen at least twice without `adb force-stop`. Verify the old disposed process ends and each reopened app reaches the mode selector. Separately verify Home/resume and cancelled exit keep the same running process. Test existing resources/save data and offline reopen. A force-stop-only cold-start test hides the disposed-singleton black-screen bug. `ForgeDisposeLifecycleTest` guards duplicate backend disposal; it does not replace an actual Android lifecycle test.

In order to build and sign the android release, you will need the `forge.keystore` file (which is not present in the repository).  This file will need to be placed in the `forge-gui-android` folder.  This file should **never** be committed to the repository.

In preparation for the android release, update the version recorded in the following files:

```
forge-gui-android/pom.xml
forge-gui-mobile/src/forge/Forge.java
```

In the first file, you're looking for `alpha-version` and setting the string accordingly.
In the last one, you're looking for the declaration of `CURRENT_VERSION` and setting it to the same value.

Commit the changes to these files to the repository with an appropriately descriptive commit message.

A script such as the following will compile and sign the android build:

```
export ANDROID_HOME=/opt/android-sdk/
export _JAVA_OPTIONS="-Xmx2g"
mvn -U -B clean \\
    -P android-release-build,android-release-sign \\
    install \\
    -Dsign.keystore=forge.keystore \\
    -Dsign.alias=Forge \\
    -Dsign.storepass=${FORGE_STOREPASS} \\
    -Dsign.keypass=${FORGE_KEYPASS}
```

Once the above build has successfully completed and passed any desired testing, the following will build, sign, and publish the build:

```
export ANDROID_HOME=/opt/android-sdk/
export _JAVA_OPTIONS="-Xmx2g"
mvn -U -B clean \\
    -P android-release-build,android-release-sign,android-release-upload \\
    install \\
    -Dsign.keystore=forge.keystore \\
    -Dsign.alias=Forge \\
    -Dsign.storepass=${FORGE_STOREPASS} \\
    -Dsign.keypass=${FORGE_KEYPASS} \\
    -Dcardforge.user=${FORGE_FTP_USER} \\
    -Dcardforge.pass=${FORGE_FTP_PASS}
```
