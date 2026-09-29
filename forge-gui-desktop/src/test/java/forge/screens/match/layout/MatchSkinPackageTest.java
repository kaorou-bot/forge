package forge.screens.match.layout;

import com.google.gson.JsonParser;
import forge.card.MagicColor;
import forge.game.GameView;
import forge.game.player.PlayerView;
import forge.game.spellability.StackItemView;
import forge.util.collect.FCollection;
import java.awt.Color;
import java.awt.Rectangle;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.testng.Assert;
import org.testng.annotations.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MatchSkinPackageTest {
    private static final String MINIMAL = """
            {"version":3,"id":"test-skin","regions":[{"bounds":[0,0.1,1,0.9],"documents":["remaining"]}],
             "scene":{"widgets":{"PHASES_ACTIVE":[0,0,1,0.1]}}}
            """;

    @Test public void emptyManaAndStackFollowGameState() {
        final var player = mock(PlayerView.class);
        final var game = mock(GameView.class);
        final var stack = new FCollection<StackItemView>();
        when(game.getStack()).thenReturn(stack);
        Assert.assertFalse(MatchVisibility.MANA_NONEMPTY.test(game, player));
        Assert.assertFalse(MatchVisibility.STACK_NONEMPTY.test(game, player));
        when(player.getMana(MagicColor.COLORLESS)).thenReturn(1);
        Assert.assertTrue(MatchVisibility.MANA_NONEMPTY.test(game, player));
        when(player.getMana(MagicColor.COLORLESS)).thenReturn(0);
        when(player.getMana(MagicColor.BLUE)).thenReturn(2);
        Assert.assertTrue(MatchVisibility.MANA_NONEMPTY.test(game, player));
        stack.add(mock(StackItemView.class));
        Assert.assertTrue(MatchVisibility.STACK_NONEMPTY.test(game, player));
        stack.clear();
        Assert.assertFalse(MatchVisibility.STACK_NONEMPTY.test(game, player));
        Assert.assertFalse(MatchVisibility.MANA_NONEMPTY.test(null, null));
        Assert.assertFalse(MatchVisibility.STACK_NONEMPTY.test(null, null));
    }

    @Test public void realSkinOwnsEveryDocumentAndProtectsTheHand() throws Exception {
        final Path skin = Path.of("../skins/dusk-sanctum").toAbsolutePath().normalize();
        try (var reader = Files.newBufferedReader(skin.resolve("match-ui.json"))) {
            final var layout = MatchUiLayout.read(reader, skin);
            final var docs = List.of("FIELD_0", "FIELD_1", "HAND_0", "REPORT_STACK", "REPORT_MESSAGE",
                    "REPORT_LOG", "REPORT_COMBAT", "REPORT_DEPENDENCIES", "BUTTON_DOCK", "CARD_PICTURE", "CARD_DETAIL");
            Assert.assertTrue(layout.scene().supports(docs));
            Assert.assertNotNull(layout.scene().appearance().background());
            Assert.assertTrue(layout.scene().appearance().font(14).canDisplay('暮'));
            Assert.assertEquals(layout.scene().visibility().get("FIELD_0.MANA"), MatchVisibility.MANA_NONEMPTY);
            final var fixed = layout.arrange(docs).stream().flatMap(c -> c.documents().stream()).toList();
            Assert.assertFalse(fixed.contains("REPORT_STACK"));
            Assert.assertFalse(fixed.contains("BUTTON_DOCK"));
            Assert.assertFalse(fixed.contains("REPORT_MESSAGE"));
            Assert.assertTrue(layout.arrange(docs).stream().anyMatch(c -> c.documents().equals(List.of("HAND_0"))));
            Assert.assertEquals(fixed.size() + 3, docs.size());
            final var missingCancel = new java.util.HashMap<>(layout.scene().widgets());
            missingCancel.remove("PROMPT_CANCEL");
            Assert.expectThrows(IllegalArgumentException.class, () -> new MatchSceneLayout(missingCancel));
            final var illegal = new MatchSceneLayout(layout.scene().widgets(), MatchSurfaceStyle.CLEAR,
                    Map.of(), Map.of(), Map.of(), Map.of("REPORT_STACK", new MatchFloatingSpec(
                            new MatchUiLayout.Bounds(0.2, 0.8, 0.2, 0.1), MatchVisibility.STACK_NONEMPTY, true)), null);
            Assert.expectThrows(IllegalArgumentException.class, () -> illegal.validate(layout.regions()));
            Assert.expectThrows(IllegalArgumentException.class, () -> new MatchSceneLayout(layout.scene().widgets(),
                    MatchSurfaceStyle.CLEAR, Map.of(), Map.of(), Map.of("FIELD_0.LIFE", MatchVisibility.MANA_NONEMPTY),
                    Map.of(), null));
        }
        Assert.assertEquals(MatchFloatingPanel.constrained(new Rectangle(-8, 999, 200, 250), 800, 600),
                new Rectangle(0, 350, 200, 250));
    }

    @Test public void themeRestoresExistingAndNewChildren() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            final var theme = MatchSkinTheme.read(JsonParser.parseString("""
                    {"text":"#F0E0D0","styles":{"default":{"fontSize":17},"button":{"fill":"#112233"}}}
                    """).getAsJsonObject(), null);
            final var root = new javax.swing.JPanel();
            final var label = new javax.swing.JLabel("Old");
            label.setForeground(Color.RED);
            final var font = label.getFont();
            root.add(label);
            final var restore = theme.apply(root, "document");
            final var added = new javax.swing.JLabel("New");
            added.setForeground(Color.BLUE);
            root.add(added);
            Assert.assertEquals(label.getForeground(), new Color(0xF0E0D0));
            Assert.assertEquals(added.getFont().getSize(), 17);
            root.remove(added);
            Assert.assertEquals(added.getForeground(), Color.BLUE, "Detached dynamic rows restore immediately");
            Assert.assertEquals(added.getContainerListeners().length, 0);
            root.add(added);
            restore.run();
            Assert.assertEquals(label.getForeground(), Color.RED);
            Assert.assertEquals(label.getFont(), font);
            Assert.assertEquals(added.getForeground(), Color.BLUE);
            Assert.assertNull(label.getClientProperty(MatchSkinTheme.STYLE_PROPERTY));
            Assert.assertEquals(root.getContainerListeners().length, 0);
        });
    }

    @Test public void actualPackageWithFontCanBeImportedAndRemovedOnWindows() throws Exception {
        final Path temp = Files.createTempDirectory("forge-real-skin-");
        final Path skin = Path.of("../skins/dusk-sanctum").toAbsolutePath().normalize();
        try {
            final Path archive = temp.resolve("skin.zip");
            try (var zip = new ZipOutputStream(Files.newOutputStream(archive)); var files = Files.walk(skin)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    zip.putNextEntry(new ZipEntry(skin.relativize(file).toString().replace('\\', '/')));
                    Files.copy(file, zip);
                    zip.closeEntry();
                }
            }
            final String name = MatchSkinPackages.install(archive, temp.resolve("installed"));
            Assert.assertTrue(Files.isRegularFile(temp.resolve("installed").resolve(name).resolve("match-ui.json")));
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
            }
        }
    }

    @Test public void importIsAtomicAndRejectsEscapesAndExecutableFiles() throws Exception {
        final Path temp = Files.createTempDirectory("forge-skin-test-");
        try {
            final Path installed = temp.resolve("installed");
            final Path archive = temp.resolve("skin.zip");
            zip(archive, Map.of("match-ui.json", MINIMAL, "README.md", "Test"));
            final String name = MatchSkinPackages.install(archive, installed);
            Assert.assertTrue(Files.isRegularFile(installed.resolve(name).resolve("match-ui.json")));
            for (String illegal : List.of("../escaped.txt", "C:/escaped.txt", "code.class", "run.js")) {
                zip(archive, Map.of("match-ui.json", MINIMAL, illegal, "bad"));
                Assert.expectThrows(IOException.class, () -> MatchSkinPackages.install(archive, installed));
            }
            zip(archive, Map.of("match-ui.json", MINIMAL.replace("PHASES_ACTIVE", "INVALID")));
            Assert.expectThrows(IOException.class, () -> MatchSkinPackages.install(archive, installed));
            try (var entries = Files.list(installed)) {
                Assert.assertEquals(entries.count(), 1L, "Failed imports leave the existing package untouched");
            }
            Assert.assertFalse(Files.exists(temp.resolve("escaped.txt")));
            Files.writeString(temp.resolve("secret.txt"), "outside");
            Assert.expectThrows(IOException.class, () -> MatchSkinTheme.asset(installed, "../secret.txt"));
            Assert.expectThrows(IllegalArgumentException.class, () -> MatchUiLayout.read(new StringReader(
                    MINIMAL.replace("\"version\":3", "\"version\":2").replace("\"widgets\"", "\"floating\":{},\"widgets\""))));
        } finally {
            try (var paths = Files.walk(temp)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.deleteIfExists(path); }
            }
        }
    }

    private static void zip(Path archive, Map<String, String> entries) throws IOException {
        try (var output = new ZipOutputStream(Files.newOutputStream(archive))) {
            for (var entry : entries.entrySet()) {
                output.putNextEntry(new ZipEntry(entry.getKey()));
                output.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
    }
}
