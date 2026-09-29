package forge.screens.match.layout;

import forge.gui.framework.DragCell;
import forge.gui.framework.EDocID;
import forge.gui.framework.RectangleOfDouble;
import forge.gui.framework.SResizingUtil;
import forge.localinstance.properties.ForgeConstants;
import forge.toolbox.FOptionPane;
import forge.toolbox.FSkin;
import forge.util.Localizer;
import forge.view.FView;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.swing.SwingUtilities;

/** Per-match state; the selection is desktop-only and custom positions never overwrite match.xml. */
public final class DesktopMatchUi {
    public record Provider(String id, String label, MatchUiLayoutProvider implementation) { }
    private static final Map<String, Provider> PROVIDERS = new LinkedHashMap<>();
    private static String selection;
    static {
        register("classic", "lblDesktopMatchUiClassic", MatchUiLayout::classic);
        register("arena", "lblDesktopMatchUiArena", () -> {
            try (var stream = DesktopMatchUi.class.getResourceAsStream("/match-ui/arena.json")) {
                if (stream == null) { throw new IOException("Missing built-in arena.json"); }
                try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    return MatchUiLayout.read(reader);
                }
            }
        });
        register("tabletop", "lblDesktopMatchUiTabletop", () -> readBuiltin("tabletop"));
        register("skin", "lblDesktopMatchUiSkin", () -> {
            final Path file = FSkin.getSkinDirectory().toPath().resolve("match-ui.json");
            if (!Files.exists(file)) { return MatchUiLayout.classic(); }
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return MatchUiLayout.read(reader);
            }
        });
    }

    private static MatchUiLayout readBuiltin(String name) throws IOException {
        try (var stream = DesktopMatchUi.class.getResourceAsStream("/match-ui/" + name + ".json")) {
            if (stream == null) { throw new IOException("Missing built-in layout: " + name); }
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) { return MatchUiLayout.read(reader); }
        }
    }

    /** Trusted Java extensions may register additional providers; JSON never loads executable code. */
    public static void register(String id, String labelKey, MatchUiLayoutProvider implementation) {
        if (id == null || !id.matches("[a-z][a-z0-9-]{0,63}") || labelKey == null || implementation == null
                || PROVIDERS.containsKey(id)) {
            throw new IllegalArgumentException("Invalid or duplicate desktop match UI provider: " + id);
        }
        PROVIDERS.put(id, new Provider(id, labelKey, implementation));
    }

    public static List<Provider> providers() { return List.copyOf(PROVIDERS.values()); }

    private static Path settingsFile() {
        return Path.of(ForgeConstants.USER_PREFS_DIR, "desktop-match-ui.properties");
    }

    public static String selection() {
        if (selection == null) {
            final Properties settings = new Properties();
            if (Files.exists(settingsFile())) {
                try (var reader = Files.newBufferedReader(settingsFile(), StandardCharsets.UTF_8)) {
                    settings.load(reader);
                } catch (IOException | IllegalArgumentException ex) {
                    System.err.println("Cannot read desktop match UI selection: " + ex.getMessage());
                }
            }
            selection = settings.getProperty("provider", "classic");
        }
        return selection;
    }

    public static void select(String id) throws IOException {
        if (!PROVIDERS.containsKey(id)) { throw new IllegalArgumentException("Unknown provider: " + id); }
        final Properties settings = new Properties();
        settings.setProperty("provider", id);
        Files.createDirectories(settingsFile().getParent());
        try (var writer = Files.newBufferedWriter(settingsFile(), StandardCharsets.UTF_8)) {
            settings.store(writer, "Desktop match UI (does not affect mobile)");
        }
        selection = id;
    }

    private MatchUiLayout layout = MatchUiLayout.classic();
    private List<MatchUiLayout.Cell> cells = List.of();
    private Path savedLayout;
    private String lastError;
    private MatchSceneView sceneView;

    public MatchUiLayout layout() { return layout; }
    public Path savedLayout() { return savedLayout; }
    public boolean isCustom() { return !layout.isClassic(); }
    public boolean isScene() { return layout.scene() != null; }

    public void refreshScene(forge.screens.match.CMatchUI match) {
        if (!isScene() || !match.isCurrentScreen()) { return; }
        if (sceneView == null) { sceneView = new MatchSceneView(match, layout.scene()); }
        sceneView.refresh();
    }

    public void resizeScene() {
        if (sceneView != null) { sceneView.resize(); }
    }

    /** Resolve and validate everything before SLayoutIO removes the current cells. */
    public void prepare(List<String> documents) {
        if (sceneView != null) { sceneView.dispose(); sceneView = null; }
        try {
            final Provider provider = PROVIDERS.get(selection());
            if (provider == null) { throw new IllegalArgumentException("Unknown provider: " + selection()); }
            MatchUiLayout candidate = provider.implementation().load();
            // A two-player skin must not hide extra players or controlled hands.
            if (candidate.scene() != null && !candidate.scene().supports(documents)) {
                candidate = readBuiltin("arena");
            }
            final List<MatchUiLayout.Cell> plan = candidate.arrange(documents);
            final String identity = selection() + "\n" + FSkin.getSkinDirectory() + "\n"
                    + candidate.id() + "\n" + candidate.regions() + "\n" + candidate.scene() + "\n" + documents;
            final Path saved = candidate.isClassic() ? null : Path.of(ForgeConstants.USER_PREFS_DIR,
                    "match-ui-" + digest(identity) + ".xml");
            layout = candidate;
            cells = plan;
            savedLayout = saved;
            lastError = null;
        } catch (IOException | RuntimeException ex) {
            layout = MatchUiLayout.classic();
            cells = List.of();
            savedLayout = null;
            final String error = selection() + ": " + ex.getMessage();
            System.err.println("Desktop match UI fallback: " + error);
            if (!error.equals(lastError)) {
                lastError = error;
                SwingUtilities.invokeLater(() -> FOptionPane.showErrorDialog(
                        Localizer.getInstance().getMessage("lblDesktopMatchUiError") + "\n" + error));
            }
        }
    }

    /** Install the default plan, including intentionally empty slots (e.g. a spectator's hand area). */
    public void install() {
        final FView view = FView.SINGLETON_INSTANCE;
        for (DragCell oldCell : view.getDragCells()) {
            for (var doc : oldCell.getDocs()) { doc.setParentCell(null); }
        }
        view.removeAllDragCells();
        for (MatchUiLayout.Cell definition : cells) {
            final DragCell cell = new DragCell();
            cell.setSceneMode(isScene());
            final var b = definition.bounds();
            cell.setRoughBounds(new RectangleOfDouble(b.x(), b.y(), b.w(), b.h()));
            view.addDragCell(cell);
            for (String id : definition.documents()) { cell.addDoc(EDocID.valueOf(id).getDoc()); }
        }
        SResizingUtil.resizeWindow();
    }

    /** Restore the validated plan if an imported XML layout hides a required live document. */
    public void ensureDocuments(List<String> documents) {
        final var present = FView.SINGLETON_INSTANCE.getDragCells().stream()
                .flatMap(cell -> cell.getDocs().stream()).toList();
        if (new java.util.HashSet<>(present).size() != present.size()) {
            install();
            return;
        }
        for (DragCell cell : FView.SINGLETON_INSTANCE.getDragCells()) {
            if (cell.getDocs().contains(EDocID.REPORT_MESSAGE.getDoc()) && cell.getDocs().size() != 1) {
                install();
                return;
            }
        }
        for (String id : documents) {
            if (!present.contains(EDocID.valueOf(id).getDoc())) {
                install();
                return;
            }
        }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)), 0, 12);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
