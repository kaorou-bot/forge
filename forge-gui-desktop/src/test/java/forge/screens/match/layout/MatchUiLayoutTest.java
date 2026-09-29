package forge.screens.match.layout;

import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.testng.Assert;
import org.testng.annotations.Test;

public class MatchUiLayoutTest {
    private static MatchUiLayout arena() throws Exception {
        try (var stream = MatchUiLayoutTest.class.getResourceAsStream("/match-ui/arena.json")) {
            Assert.assertNotNull(stream, "The example must be included in the desktop distribution");
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return MatchUiLayout.read(reader);
            }
        }
    }

    private static List<String> documents(int players, int hands, boolean dev) {
        final List<String> docs = new ArrayList<>();
        for (int i = 0; i < players; i++) { docs.add("FIELD_" + i); }
        for (int i = 0; i < hands; i++) { docs.add("HAND_" + i); }
        docs.addAll(List.of("REPORT_STACK", "REPORT_COMBAT", "REPORT_LOG", "REPORT_DEPENDENCIES",
                "REPORT_MESSAGE", "BUTTON_DOCK", "CARD_PICTURE", "CARD_DETAIL"));
        if (dev) { docs.add("DEV_MODE"); }
        return docs;
    }

    @Test
    public void exampleKeepsEveryDocumentOnceForMultiplayerSpectatorsAndControlledHands() throws Exception {
        final MatchUiLayout layout = arena();
        for (int players = 2; players <= 8; players++) {
            for (int hands = 0; hands <= players; hands++) {
                for (boolean dev : List.of(false, true)) {
                    final List<String> docs = documents(players, hands, dev);
                    final var cells = layout.arrange(docs);
                    final var assigned = cells.stream().flatMap(c -> c.documents().stream()).toList();
                    Assert.assertEquals(assigned.size(), docs.size());
                    Assert.assertEquals(new HashSet<>(assigned), new HashSet<>(docs));
                    Assert.assertEquals(cells.stream().mapToDouble(c -> c.bounds().w() * c.bounds().h()).sum(), 1, 0.000001);
                    for (int i = 0; i < cells.size(); i++) {
                        for (int j = 0; j < i; j++) {
                            Assert.assertFalse(cells.get(i).bounds().overlaps(cells.get(j).bounds()));
                        }
                    }
                    Assert.assertTrue(cells.stream().anyMatch(c -> c.documents().equals(List.of("REPORT_MESSAGE"))));
                }
            }
        }
    }

    @Test
    public void supportsSparseHandIdsAfterControlChange() throws Exception {
        final List<String> docs = documents(4, 0, false);
        docs.add("HAND_2");
        Assert.assertTrue(arena().arrange(docs).stream().anyMatch(c -> c.documents().equals(List.of("HAND_2"))));
    }

    @Test
    public void classicDelegatesToExistingLayoutSystem() {
        Assert.assertTrue(MatchUiLayout.classic().isClassic());
        Assert.assertTrue(MatchUiLayout.classic().arrange(documents(4, 1, false)).isEmpty());
        Assert.assertSame(MatchUiLayout.classic().fieldLayout(), MatchFieldLayout.CLASSIC);
    }

    @Test
    public void rejectsUnsafeGeometryAndUnsupportedConfig() {
        for (double[] b : List.of(new double[]{-0.1, 0, 1, 1}, new double[]{0, 0, 0, 1},
                new double[]{0, 0, 1.1, 1}, new double[]{Double.NaN, 0, 1, 1},
                new double[]{0, 0, Double.POSITIVE_INFINITY, 1})) {
            Assert.expectThrows(IllegalArgumentException.class, () -> new MatchUiLayout.Bounds(b[0], b[1], b[2], b[3]));
        }
        Assert.expectThrows(IllegalArgumentException.class, () -> read("{\"version\":3}"));
        Assert.expectThrows(IllegalArgumentException.class, () -> read("{\"version\":1,\"script\":\"execute\"}"));
        Assert.expectThrows(RuntimeException.class, () -> read("{\"version\":1}"));
        Assert.expectThrows(IllegalArgumentException.class, () -> new MatchUiLayout.Region(
                new MatchUiLayout.Bounds(0, 0, 1, 1), List.of("HOME_CONSTRUCTED"), MatchUiLayout.Split.TABS));
    }

    @Test
    public void rejectsGapsOverlapsDuplicateAndOmittedDocuments() {
        final var full = new MatchUiLayout.Bounds(0, 0, 1, 1);
        final var region = new MatchUiLayout.Region(full, List.of("fields"), MatchUiLayout.Split.TABS);
        Assert.expectThrows(IllegalArgumentException.class,
                () -> new MatchUiLayout("bad", List.of(region, region), MatchFieldLayout.CLASSIC));
        Assert.expectThrows(IllegalArgumentException.class, () -> new MatchUiLayout("bad", List.of(
                new MatchUiLayout.Region(new MatchUiLayout.Bounds(0, 0, 0.5, 1), List.of("fields"),
                        MatchUiLayout.Split.TABS)), MatchFieldLayout.CLASSIC));
        final var omitted = new MatchUiLayout("bad", List.of(region), MatchFieldLayout.CLASSIC);
        Assert.expectThrows(IllegalArgumentException.class, () -> omitted.arrange(documents(2, 1, false)));
        final var duplicate = new MatchUiLayout("bad", List.of(new MatchUiLayout.Region(full,
                List.of("fields", "FIELD_0", "remaining"), MatchUiLayout.Split.TABS)), MatchFieldLayout.CLASSIC);
        Assert.expectThrows(IllegalArgumentException.class, () -> duplicate.arrange(documents(2, 1, false)));
    }

    @Test
    public void actionsCannotBeHiddenBehindAnotherTab() {
        final var layout = new MatchUiLayout("bad", List.of(new MatchUiLayout.Region(
                new MatchUiLayout.Bounds(0, 0, 1, 1), List.of("remaining"), MatchUiLayout.Split.TABS)),
                MatchFieldLayout.CLASSIC);
        Assert.expectThrows(IllegalArgumentException.class, () -> layout.arrange(documents(2, 1, false)));
    }

    @Test
    public void fieldLayoutReparentsLiveComponentsAndResizesWithoutLosingActions() throws Exception {
        final var layout = arena();
        SwingUtilities.invokeAndWait(() -> {
            final JPanel host = new JPanel();
            final Map<MatchFieldLayout.Part, JComponent> parts = new EnumMap<>(MatchFieldLayout.Part.class);
            final int[] clicks = {0};
            for (var part : MatchFieldLayout.Part.values()) {
                final JButton button = new JButton(part.name());
                button.addActionListener(e -> clicks[0]++);
                parts.put(part, button);
            }
            for (int[] size : List.of(new int[]{800, 300}, new int[]{1920, 540}, new int[]{387, 163})) {
                host.removeAll();
                layout.fieldLayout().populate(host, Map.copyOf(parts));
                host.setSize(size[0], size[1]);
                host.doLayout();
                Assert.assertEquals(host.getComponentCount(), 4);
                for (var component : parts.values()) {
                    Assert.assertSame(component.getParent(), host);
                    Assert.assertTrue(component.getWidth() > 0 && component.getHeight() > 0);
                    Assert.assertTrue(component.getX() + component.getWidth() <= host.getWidth());
                    Assert.assertTrue(component.getY() + component.getHeight() <= host.getHeight());
                }
                Assert.assertTrue(parts.get(MatchFieldLayout.Part.AVATAR).getX()
                        > parts.get(MatchFieldLayout.Part.BATTLEFIELD).getX());
            }
            ((JButton) parts.get(MatchFieldLayout.Part.AVATAR)).doClick();
            Assert.assertEquals(clicks[0], 1);
            host.removeAll();
            MatchFieldLayout.CLASSIC.populate(host, Map.copyOf(parts));
            host.doLayout();
            Assert.assertEquals(host.getComponentCount(), 4);
            ((JButton) parts.get(MatchFieldLayout.Part.AVATAR)).doClick();
            Assert.assertEquals(clicks[0], 2);
        });
    }

    @Test
    public void allFieldPartsAreRequired() {
        Assert.expectThrows(IllegalArgumentException.class, () -> MatchFieldLayout.relative(Map.of(
                MatchFieldLayout.Part.BATTLEFIELD, new MatchUiLayout.Bounds(0, 0, 1, 1))));
    }

    private static MatchUiLayout read(String json) {
        return MatchUiLayout.read(new StringReader(json));
    }
}
