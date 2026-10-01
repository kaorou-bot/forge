package forge.screens.match.layout;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Read-only bundled skin. The same source files serve the desktop JAR and independent authoring kit. */
final class BuiltinMatchSkin {
    private BuiltinMatchSkin() { }

    static MatchUiLayout loadDuskSanctum() throws IOException {
        return read(BuiltinMatchSkin.class.getResource("/match-ui/dusk-sanctum/match-ui.json"));
    }
    static MatchUiLayout loadDuskObservatory() throws IOException {
        return read(BuiltinMatchSkin.class.getResource("/match-ui/dusk-observatory/match-ui.json"));
    }

    static MatchUiLayout read(URL resource) throws IOException {
        if (resource == null) { throw new IOException("Missing bundled dusk-sanctum skin"); }
        try {
            if (resource.getProtocol().equals("file")) { return read(Path.of(resource.toURI())); }
            if (resource.getProtocol().equals("jar")) {
                final var connection = (JarURLConnection) resource.openConnection();
                connection.setUseCaches(false);
                // Font and image bytes are fully loaded before the ZIP filesystem closes. No cache or user-data writes.
                try (var fs = FileSystems.newFileSystem(Path.of(connection.getJarFileURL().toURI()), Map.of())) {
                    return read(fs.getPath("/" + connection.getEntryName()));
                }
            }
            throw new IOException("Unsupported built-in skin protocol: " + resource.getProtocol());
        } catch (URISyntaxException e) { throw new IOException("Invalid built-in skin resource URL", e); }
    }

    private static MatchUiLayout read(Path json) throws IOException {
        try (var reader = Files.newBufferedReader(json, StandardCharsets.UTF_8)) {
            return MatchUiLayout.read(reader, json.getParent());
        }
    }
}
