package forge.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Resolves the optional update and card-image mirrors used by localized builds.
 * System properties and environment variables make local testing possible,
 * while the packaged properties file supplies production defaults on Android.
 */
public final class ForgeUpdateConfig {
    private static final String CONFIG_RESOURCE = "/forge-update.properties";
    private static final Properties CONFIG = loadConfig();

    private ForgeUpdateConfig() {
    }

    public static String getUpdateBaseUrl() {
        return configuredUrl("forge.update.url", "FORGE_UPDATE_URL", "update.baseUrl");
    }

    public static String getCardImageBaseUrl() {
        return configuredUrl("forge.images.url", "FORGE_IMAGE_URL", "images.baseUrl");
    }

    /** Tokens have their own cache names and must never use the cards/ mirror. */
    public static String getTokenImageDownloadUrl(final String relativePath) {
        final String base = configuredUrl("forge.tokens.url", "FORGE_TOKEN_IMAGE_URL", "tokens.baseUrl");
        if (base.isEmpty() || relativePath == null || relativePath.isEmpty()) {
            return "";
        }
        // Preserve path separators, collector numbers and the Unicode back-face marker.
        return base + URLEncoder.encode(relativePath, StandardCharsets.UTF_8)
                .replace("+", "%20").replace("%2F", "/");
    }

    public static boolean isTokenImageMirrorUrl(final String url) {
        final String base = configuredUrl("forge.tokens.url", "FORGE_TOKEN_IMAGE_URL", "tokens.baseUrl");
        return !base.isEmpty() && url != null && url.startsWith(base);
    }

    public static boolean isCardImageMirrorEnabled() {
        return !getCardImageBaseUrl().isEmpty();
    }

    public static boolean isMirrorEnabled() {
        return !getUpdateBaseUrl().isEmpty();
    }

    public static String getManifestUrl() {
        final String baseUrl = getUpdateBaseUrl();
        return baseUrl.isEmpty() ? "" : baseUrl + "manifest-v1.properties";
    }

    public static String getCardImageBaseUrlOrDefault(final String defaultUrl) {
        final String configured = getCardImageBaseUrl();
        return configured.isEmpty() ? ensureTrailingSlash(defaultUrl) : configured;
    }

    private static String configuredUrl(final String systemProperty, final String environmentVariable,
                                        final String resourceProperty) {
        String value = System.getProperty(systemProperty, "").trim();
        if (value.isEmpty()) {
            value = System.getenv().getOrDefault(environmentVariable, "").trim();
        }
        if (value.isEmpty()) {
            value = CONFIG.getProperty(resourceProperty, "").trim();
        }
        return value.isEmpty() ? "" : ensureTrailingSlash(value);
    }

    private static String ensureTrailingSlash(final String value) {
        return value.endsWith("/") ? value : value + "/";
    }

    private static Properties loadConfig() {
        final Properties properties = new Properties();
        try (InputStream stream = ForgeUpdateConfig.class.getResourceAsStream(CONFIG_RESOURCE)) {
            if (stream != null) {
                properties.load(stream);
            }
        } catch (IOException e) {
            System.err.println("Unable to read " + CONFIG_RESOURCE + ": " + e.getMessage());
        }
        return properties;
    }
}
