package forge.screens.match.layout;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** User-only overrides, keyed by author ID rather than an installed ZIP's content hash. */
public final class MatchSkinPreferences {
    public record Panel(MatchUiLayout.Bounds bounds, boolean collapsed, boolean locked) { }
    private final Path file;
    private JsonObject data = new JsonObject();
    private final MatchSkinSettings defaults;
    public MatchSkinPreferences(Path directory, String id, MatchSkinSettings defaults) {
        if (!id.matches("[a-z][a-z0-9-]{0,63}")) { throw new IllegalArgumentException("Invalid skin ID"); }
        file = directory.resolve(id + ".json"); this.defaults = defaults;
        try {
            if (Files.exists(file)) {
                if (Files.size(file) > 131072) { throw new IOException("Oversized skin preferences"); }
                data = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                settings(); // Validate before retaining settings.
            }
        } catch (IOException | RuntimeException ex) {
            data = new JsonObject();
            // Leave a recoverable copy before the next explicit user save.
            try { if (Files.exists(file)) { Files.copy(file, file.resolveSibling(id + ".invalid.json"), StandardCopyOption.REPLACE_EXISTING); } }
            catch (IOException ignored) { System.err.println("Cannot back up skin preferences: " + file); }
            System.err.println("Ignoring invalid skin preferences: " + ex.getMessage());
        }
    }
    public MatchSkinSettings settings() {
        return data.has("settings") ? MatchSkinSettings.read(data.getAsJsonObject("settings")) : defaults;
    }
    public void settings(MatchSkinSettings settings) throws IOException {
        final var next = data.deepCopy();
        next.add("settings", new GsonBuilder().create().toJsonTree(settings)); save(next);
    }
    public Panel panel(String variant, String id) {
        try {
            final String key = variant + "/" + id;
            if (!data.has("panels") || !data.getAsJsonObject("panels").has(key)) { return null; }
            final var p = data.getAsJsonObject("panels").getAsJsonObject(key);
            return new Panel(MatchUiLayout.bounds(p.get("bounds")), p.get("collapsed").getAsBoolean(), p.get("locked").getAsBoolean());
        } catch (RuntimeException ex) { return null; }
    }
    public void panel(String variant, String id, Panel state) throws IOException {
        final var next = data.deepCopy();
        if (!next.has("panels")) { next.add("panels", new JsonObject()); }
        final var panels = next.getAsJsonObject("panels");
        final String key = variant + "/" + id;
        if (state == null) { panels.remove(key); }
        else {
            if (panels.size() >= 128 && !panels.has(key)) { panels.remove(panels.keySet().iterator().next()); }
            final var p = new JsonObject(); final var b = new com.google.gson.JsonArray();
            b.add(state.bounds().x()); b.add(state.bounds().y()); b.add(state.bounds().w()); b.add(state.bounds().h());
            p.add("bounds", b); p.addProperty("collapsed", state.collapsed()); p.addProperty("locked", state.locked()); panels.add(key, p);
        }
        save(next);
    }
    public void reset() throws IOException { save(new JsonObject()); }
    public Path file() { return file; }
    private void save(JsonObject next) throws IOException {
        Files.createDirectories(file.getParent());
        final var tmp = Files.createTempFile(file.getParent(), ".skin-prefs-", ".tmp");
        try {
            Files.writeString(tmp, new GsonBuilder().setPrettyPrinting().create().toJson(next));
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); data = next;
        } finally { Files.deleteIfExists(tmp); }
    }
}
