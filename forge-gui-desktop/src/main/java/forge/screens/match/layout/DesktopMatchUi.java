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
    private static String packageName;
    static {
        register("classic", "lblDesktopMatchUiClassic", MatchUiLayout::classic);
        register("dusk-sanctum", "lblDesktopMatchUiDuskSanctum", BuiltinMatchSkin::loadDuskSanctum);
        register("dusk-observatory", "lblDesktopMatchUiDuskObservatory", BuiltinMatchSkin::loadDuskObservatory);
        register("package", "lblDesktopMatchUiPackage", () -> MatchSkinPackages.load(packageName));
        register("skin", "lblDesktopMatchUiSkin", () -> {
            final Path file = FSkin.getSkinDirectory().toPath().resolve("match-ui.json");
            if (!Files.exists(file)) { return MatchUiLayout.classic(); }
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return MatchUiLayout.read(reader, file.getParent());
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

    /** Removed preview selections must not produce an unknown-provider dialog after upgrading. */
    static String supportedSelection(String id) {
        return PROVIDERS.containsKey(id) ? id : "classic";
    }

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
            selection = supportedSelection(settings.getProperty("provider", "classic"));
            packageName = settings.getProperty("package");
        }
        return selection;
    }

    public static void select(String id) throws IOException {
        selection();
        if (!PROVIDERS.containsKey(id)) { throw new IllegalArgumentException("Unknown provider: " + id); }
        saveSelection(settingsFile(), id, packageName);
        selection = id;
    }

    static void saveSelection(Path file, String provider, String savedPackage) throws IOException {
        final Properties settings = new Properties();
        settings.setProperty("provider", provider);
        if (savedPackage != null) { settings.setProperty("package", savedPackage); }
        Files.createDirectories(file.getParent());
        final Path temporary = Files.createTempFile(file.getParent(), ".match-ui-", ".tmp");
        try {
            try (var writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                settings.store(writer, "Desktop match UI (does not affect mobile)");
            }
            Files.move(temporary, file, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static String selectedPackage() { selection(); return packageName; }

    /** Returns whether the active scene must be rebuilt before files may be removed. */
    public static boolean forgetPackage(String name) throws IOException { return forgetPackage(name, settingsFile()); }
    static boolean forgetPackage(String name, Path file) throws IOException {
        selection();
        if (name == null || !name.equals(packageName)) { return false; }
        final boolean active = selection.equals("package");
        final String next = active ? "classic" : selection;
        saveSelection(file, next, null);
        selection = next;
        packageName = null;
        return active;
    }
    public static void selectPackage(String name) throws IOException {
        MatchSkinPackages.load(name);
        selection();
        final String previous = packageName;
        packageName = name;
        try { select("package"); } catch (IOException e) { packageName = previous; throw e; }
    }

    private MatchUiLayout layout = MatchUiLayout.classic();
    private List<MatchUiLayout.Cell> cells = List.of();
    private Path savedLayout;
    private String lastError;
    private MatchSceneView sceneView;
    private boolean creatingScene;
    private MatchUiLayout template;
    private MatchSkinPreferences preferences;
    private String variant = "base";
    private List<String> documents = List.of();
    private boolean responsiveReload;
    private MatchUiLayout validated;
    /** Decode/plan before removing live controls; malformed hot reload leaves the current scene intact. */
    public boolean preflight(List<String> available) {
        try {
            final var source = responsiveReload && template != null ? template : PROVIDERS.get(selection()).implementation().load();
            final var prefs = new MatchSkinPreferences(Path.of(ForgeConstants.USER_PREFS_DIR, "desktop-skin-experience"), source.id(), source.experience().defaults());
            final var chosen = source.experience().choose(source, Math.max(1, FView.SINGLETON_INSTANCE.getPnlContent().getWidth()),
                    (int) available.stream().filter(s -> s.startsWith("FIELD_")).count(), prefs.settings().layoutMode()).layout();
            if (chosen.scene() == null || chosen.scene().supports(available)) { chosen.arrange(available); }
            validated = source; return true;
        } catch (IOException | RuntimeException ex) {
            FOptionPane.showErrorDialog("皮肤配置未通过验证，已保留当前界面。\n" + ex.getMessage()); return false;
        }
    }
    public static DesktopMatchUi current() {
        final var view = forge.Singletons.getControl().getCurrentScreen().getView();
        return view instanceof forge.screens.match.VMatchUI match ? match.getDesktopUi() : null;
    }
    public void showSettings() {
        if (preferences == null || !isScene()) { return; }
        final var s = preferences.settings();
        final var font = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(s.fontScale(), .75, 1.75, .05));
        final var hand = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(s.handWidth(), 40, 300, 10));
        final var opacity = new javax.swing.JSlider(0, 100, (int) (s.panelOpacity() * 100));
        final var decorations = new javax.swing.JSlider(0, 100, (int) (s.decorationOpacity() * 100));
        final var compact = new javax.swing.JCheckBox("优先使用 compact 紧凑方案（皮肤需提供）", s.layoutMode().equals("COMPACT"));
        final var panel = new javax.swing.JPanel(new java.awt.GridLayout(0, 2, 8, 8));
        panel.add(new javax.swing.JLabel("字号倍率")); panel.add(font);
        panel.add(new javax.swing.JLabel("扇形手牌最大宽度")); panel.add(hand);
        panel.add(new javax.swing.JLabel("浮窗背景不透明度（不影响文字）")); panel.add(opacity);
        panel.add(new javax.swing.JLabel("装饰不透明度")); panel.add(decorations); panel.add(compact);
        final var reset = new javax.swing.JCheckBox("重置个人设置和全部浮窗位置"); panel.add(reset);
        if (javax.swing.JOptionPane.showConfirmDialog(null, panel, "皮肤个人设置 · " + variant,
                javax.swing.JOptionPane.OK_CANCEL_OPTION) != javax.swing.JOptionPane.OK_OPTION) { return; }
        try {
            if (reset.isSelected()) { preferences.reset(); }
            else { preferences.settings(new MatchSkinSettings(((Number) font.getValue()).doubleValue(), ((Number) hand.getValue()).intValue(),
                    opacity.getValue() / 100.0, decorations.getValue() / 100.0, compact.isSelected() ? "COMPACT" : "AUTO")); }
            responsiveReload = true; forge.gui.framework.SLayoutIO.revertLayoutNow();
        } catch (IOException ex) { FOptionPane.showErrorDialog(ex.getMessage()); }
    }
    private final javax.swing.Timer resizeTimer = new javax.swing.Timer(250, e -> adapt());
    public MatchSkinPreferences preferences() { return preferences; }
    public MatchUiLayout template() { return template; }
    public String variant() { return variant; }
    public void preview(MatchUiLayout draft) {
        final var previous = template; template = draft; responsiveReload = true;
        if (!preflight(documents)) { template = previous; responsiveReload = false; return; }
        forge.gui.framework.SLayoutIO.revertLayoutNow();
    }
    private int playerCount() { return (int) documents.stream().filter(s -> s.startsWith("FIELD_")).count(); }
    private MatchSkinExperience.Choice choice(MatchUiLayout source) {
        return source.experience().choose(source, Math.max(1, FView.SINGLETON_INSTANCE.getPnlContent().getWidth()),
                playerCount(), preferences.settings().layoutMode());
    }
    private void adapt() {
        resizeTimer.stop();
        if (current() != this || template == null || preferences == null || creatingScene || template.experience().variants().isEmpty()) { return; }
        if (!choice(template).id().equals(variant)) {
            responsiveReload = true;
            forge.gui.framework.SLayoutIO.revertLayoutNow();
        }
    }

    public MatchUiLayout layout() { return layout; }
    public Path savedLayout() { return savedLayout; }
    public boolean isCustom() { return !layout.isClassic(); }
    public boolean isScene() { return layout.scene() != null; }

    public void refreshScene(forge.screens.match.CMatchUI match) {
        if (!isScene() || !match.isCurrentScreen() || creatingScene) { return; }
        if (sceneView == null) {
            // Populating a floating document calls its controller, which can request another scene refresh.
            creatingScene = true;
            try { sceneView = new MatchSceneView(match, layout, preferences, variant); }
            finally { creatingScene = false; }
        }
        sceneView.refresh();
    }

    public void resizeScene() {
        if (sceneView != null) { sceneView.resize(); }
        resizeTimer.setRepeats(false); resizeTimer.restart();
    }

    public void refreshExistingScene() {
        if (sceneView != null) { sceneView.refresh(); }
    }

    /** Resolve and validate everything before SLayoutIO removes the current cells. */
    public void prepare(List<String> documents) {
        resizeTimer.stop();
        this.documents = List.copyOf(documents);
        if (sceneView != null) { sceneView.dispose(); sceneView = null; }
        for (DragCell cell : FView.SINGLETON_INSTANCE.getDragCells()) { cell.releaseSceneSurface(); }
        try {
            final Provider provider = PROVIDERS.get(selection());
            if (provider == null) { throw new IllegalArgumentException("Unknown provider: " + selection()); }
            MatchUiLayout candidate = validated != null ? validated : responsiveReload && template != null ? template : provider.implementation().load();
            validated = null;
            responsiveReload = false;
            template = candidate;
            preferences = new MatchSkinPreferences(Path.of(ForgeConstants.USER_PREFS_DIR, "desktop-skin-experience"),
                    candidate.id(), candidate.experience().defaults());
            final var chosen = choice(candidate); candidate = chosen.layout(); variant = chosen.id();
            final var settings = preferences.settings();
            if (candidate.scene() != null && candidate.scene().appearance() != null) {
                final var cards = candidate.cards(); final var hand = cards.hand();
                final HandLayoutStrategy adjusted = hand == null ? null : new HandLayoutStrategy() {
                    @Override public double hoverLift() { return hand.hoverLift(); }
                    @Override public List<Placement> arrange(int count, int width, int height, int max) {
                        return hand.arrange(count, width, height, Math.min(max, settings.handWidth()));
                    }
                };
                candidate = new MatchUiLayout(candidate.id(), candidate.regions(), candidate.fieldLayout(),
                        candidate.scene().withAppearance(candidate.scene().appearance().withSettings(settings)),
                        new MatchCardPresentation(adjusted, cards.overlay(), cards.battlefield()), candidate.experience());
            }
            // Keep the capacity-safe internal layout, without exposing the old Arena demo in the menu.
            if (candidate.scene() != null && !candidate.scene().supports(documents)) {
                candidate = readBuiltin("arena");
            }
            final List<MatchUiLayout.Cell> plan = candidate.arrange(documents);
            final String identity = selection() + "\n" + FSkin.getSkinDirectory() + "\n"
                    + candidate.id() + "\n" + variant + "\n" + template.experience().source() + "\n" + candidate.regions() + "\n" + documents;
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
            if (isScene()) { cell.setSceneTheme(layout.scene().appearance()); }
            if (isScene()) { cell.setSceneSurfaceResolver(layout.scene()::styleFor); }
            if (isScene()) { cell.setSceneSurface(layout.scene().styleFor(definition.documents().isEmpty()
                    ? "remaining" : definition.documents().get(0))); }
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
            if (isScene() && layout.scene().replacesDocument(id)) { continue; }
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
