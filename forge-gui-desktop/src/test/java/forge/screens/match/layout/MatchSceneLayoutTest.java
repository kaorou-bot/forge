package forge.screens.match.layout;

import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.Test;

public class MatchSceneLayoutTest {
    private static MatchUiLayout tabletop() throws Exception {
        try (var stream = MatchSceneLayoutTest.class.getResourceAsStream("/match-ui/tabletop.json")) {
            Assert.assertNotNull(stream);
            return MatchUiLayout.read(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }

    @Test public void scenePreservesLiveDocumentsAndChecksPlayerCapacity() throws Exception {
        final var layout = tabletop();
        final var docs = new ArrayList<>(List.of("FIELD_0", "FIELD_1", "HAND_0", "REPORT_STACK", "REPORT_COMBAT",
                "REPORT_LOG", "REPORT_DEPENDENCIES", "REPORT_MESSAGE", "BUTTON_DOCK", "CARD_PICTURE", "CARD_DETAIL"));
        Assert.assertTrue(layout.scene().supports(docs));
        final var actual = layout.arrange(docs).stream().flatMap(c -> c.documents().stream()).toList();
        Assert.assertEquals(actual.size(), docs.size() - 1);
        final var represented = new HashSet<>(actual);
        docs.stream().filter(layout.scene()::replacesDocument).forEach(represented::add);
        Assert.assertEquals(represented, new HashSet<>(docs));
        Assert.assertFalse(actual.contains("BUTTON_DOCK"), "The new action controls replace the old dock");
        Assert.assertTrue(layout.arrange(docs).stream().anyMatch(cell -> cell.documents().equals(List.of("HAND_0"))),
                "The local hand must keep its own visible document");
        Assert.assertNotNull(layout.cards().hand());
        Assert.assertNotNull(layout.cards().battlefield());
        final var tabbedHand = layout.regions().stream().map(region -> {
            if (region.documents().contains("hands")) {
                return new MatchUiLayout.Region(region.bounds(), List.of("hands", "CARD_DETAIL"), MatchUiLayout.Split.TABS);
            }
            if (region.documents().contains("CARD_DETAIL")) {
                return new MatchUiLayout.Region(region.bounds(), List.of("CARD_PICTURE"), region.split());
            }
            return region;
        }).toList();
        final var hiddenHand = new MatchUiLayout("invalid", tabbedHand, layout.fieldLayout(), layout.scene(), layout.cards());
        Assert.expectThrows(IllegalArgumentException.class, () -> hiddenHand.arrange(docs));
        docs.add("HAND_1");
        Assert.assertFalse(layout.scene().supports(docs), "Multiple controlled hands require the arena fallback");
        docs.remove("HAND_1");
        docs.remove("HAND_0");
        Assert.assertTrue(layout.scene().supports(docs), "Spectator without visible hands");
        docs.add("FIELD_2");
        Assert.assertFalse(layout.scene().supports(docs), "Extra player must not lose avatar/details");
        docs.remove("FIELD_2");
        docs.remove("FIELD_1");
        Assert.assertFalse(layout.scene().supports(docs), "Missing field must not produce dangling widgets");
    }

    @Test public void sceneRejectsInteractiveOverlapAndUnknownWidgets() {
        final String json = """
                {"version":2,"id":"test","regions":[{"bounds":[0,0,1,0.5],"documents":["remaining"]}],
                 "scene":{"widgets":{"PHASES_ACTIVE":[0,0.4,1,0.2]}}}
                """;
        Assert.expectThrows(IllegalArgumentException.class, () -> MatchUiLayout.read(new StringReader(json)));
        Assert.expectThrows(IllegalArgumentException.class, () -> MatchUiLayout.read(new StringReader(
                json.replace("PHASES_ACTIVE", "PRIVATE_HAND"))));
        Assert.expectThrows(IllegalArgumentException.class, () -> MatchUiLayout.read(new StringReader(
                json.replace("\"version\":2", "\"version\":1"))));
    }

    @Test public void granularWidgetsReplaceCompositesAndRequireUsablePlayerControls() throws Exception {
        final var scene = tabletop().scene();
        Assert.assertFalse(scene.widgets().containsKey("FIELD_0.DETAILS"));
        Assert.assertFalse(scene.widgets().containsKey("FIELD_0.AVATAR"));
        Assert.assertTrue(scene.widgets().containsKey("STACK_STATUS"));
        final var widgets = new java.util.HashMap<>(scene.widgets());
        final var avatar = widgets.get("FIELD_0.AVATAR_IMAGE");
        widgets.put("FIELD_0.AVATAR", avatar);
        Assert.expectThrows(IllegalArgumentException.class, () -> new MatchSceneLayout(widgets));
        widgets.remove("FIELD_0.AVATAR");
        widgets.put("FIELD_0.DETAILS", avatar);
        Assert.expectThrows(IllegalArgumentException.class, () -> new MatchSceneLayout(widgets));
        widgets.remove("FIELD_0.DETAILS");
        widgets.remove("FIELD_0.MANA");
        Assert.assertFalse(new MatchSceneLayout(widgets).supports(List.of("FIELD_0", "FIELD_1", "HAND_0")));
        for (String color : List.of("W", "U", "B", "R", "G", "C")) { widgets.put("FIELD_0.MANA_" + color, avatar); }
        Assert.assertTrue(new MatchSceneLayout(widgets).supports(List.of("FIELD_0", "FIELD_1", "HAND_0")));
        widgets.remove("FIELD_0.OTHER_ZONES");
        Assert.assertFalse(new MatchSceneLayout(widgets).supports(List.of("FIELD_0", "FIELD_1", "HAND_0")));
    }

    @Test public void zoneRendererAndSurfaceConfigurationAreValidated() throws Exception {
        final var scene = tabletop().scene();
        final var alternate = new MatchSceneLayout(scene.widgets(), MatchSurfaceStyle.CLEAR, java.util.Map.of(),
                java.util.Map.of("FIELD_0.ZONE_EXILE", "ZONE_BUTTON"));
        Assert.assertEquals(alternate.renderers().get("FIELD_0.ZONE_EXILE"), "ZONE_BUTTON");
        Assert.expectThrows(IllegalArgumentException.class, () -> new MatchSceneLayout(scene.widgets(),
                MatchSurfaceStyle.CLEAR, java.util.Map.of(), java.util.Map.of("FIELD_0.LIFE", "ZONE_BUTTON")));
        Assert.expectThrows(IllegalArgumentException.class, () -> MatchSurfaceStyle.read(
                com.google.gson.JsonParser.parseString("{\"background\":\"false\"}").getAsJsonObject()));
        final var visible = new MatchSurfaceStyle(true, true, true);
        final var styled = new MatchSceneLayout(scene.widgets(), MatchSurfaceStyle.CLEAR,
                java.util.Map.of("hands", visible), java.util.Map.of());
        Assert.assertEquals(styled.styleFor("HAND_0"), visible);
        Assert.assertEquals(styled.styleFor("REPORT_STACK"), MatchSurfaceStyle.CLEAR);
    }

    @Test public void transparentSurfacesPreserveControlsAndRestoreTheirOriginalDecoration() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            final var root = new javax.swing.JPanel(new java.awt.BorderLayout());
            final var nested = new javax.swing.JPanel();
            final var button = new javax.swing.JButton("Choose");
            final var originalButtonBorder = button.getBorder();
            final var originalBorder = javax.swing.BorderFactory.createLineBorder(java.awt.Color.RED, 3);
            nested.setBorder(originalBorder);
            nested.add(button);
            final var scroll = new javax.swing.JScrollPane(nested);
            scroll.setViewportBorder(originalBorder);
            root.add(scroll);
            final Runnable restore = MatchSurfaceStyle.CLEAR.apply(root);
            Assert.assertFalse(root.isOpaque());
            Assert.assertFalse(nested.isOpaque());
            Assert.assertFalse(scroll.getViewport().isOpaque());
            Assert.assertNull(nested.getBorder());
            Assert.assertNull(scroll.getViewportBorder());
            Assert.assertSame(button.getBorder(), originalButtonBorder);
            restore.run();
            Assert.assertTrue(nested.isOpaque());
            Assert.assertSame(nested.getBorder(), originalBorder);
            Assert.assertSame(scroll.getViewportBorder(), originalBorder);
        });
    }

    @Test public void transparentFPanelActuallyStopsPaintingItsOldBox() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            final var panel = new forge.toolbox.FPanel();
            panel.setSize(80, 60);
            final boolean originalBorder = panel.isBorderToggle();
            final boolean originalBackground = panel.isBackgroundToggle();
            final Runnable restore = MatchSurfaceStyle.CLEAR.apply(panel);
            final var pixels = new java.awt.image.BufferedImage(80, 60, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            final var graphics = pixels.createGraphics();
            try { panel.paint(graphics); } finally { graphics.dispose(); }
            Assert.assertEquals(pixels.getRGB(40, 30) >>> 24, 0, "The panel must not fill its old rectangle");
            Assert.assertEquals(pixels.getRGB(0, 0) >>> 24, 0, "The panel must not draw its old border");
            restore.run();
            Assert.assertEquals(panel.isBorderToggle(), originalBorder);
            Assert.assertEquals(panel.isBackgroundToggle(), originalBackground);
        });
    }

    @Test public void rotatedHandsFitAndHitTestsFollowVisibleGeometry() {
        final var strategy = HandLayoutStrategy.fan(60);
        for (int count : new int[]{0, 1, 2, 7, 30, 100}) {
            for (int[] size : List.of(new int[]{800, 180}, new int[]{320, 100}, new int[]{1500, 400})) {
                final var placements = strategy.arrange(count, size[0], size[1], 200);
                Assert.assertEquals(placements.size(), count);
                for (var p : placements) {
                    Assert.assertTrue(p.contains(p.x() + p.width() / 2.0, p.y() + p.height() / 2.0));
                    for (int sx : new int[]{-1, 1}) {
                        for (int sy : new int[]{-1, 1}) {
                            final var corner = p.corner(sx * p.width() / 2.0, sy * p.height() / 2.0);
                            Assert.assertTrue(corner.getX() >= 0 && corner.getX() <= size[0]);
                            Assert.assertTrue(corner.getY() >= 0 && corner.getY() <= size[1]);
                            final var outside = p.corner(sx * (p.width() / 2.0 + 2), sy * (p.height() / 2.0 + 2));
                            Assert.assertFalse(p.contains(outside.getX(), outside.getY()));
                        }
                    }
                }
            }
        }
        Assert.expectThrows(IllegalArgumentException.class, () -> HandLayoutStrategy.fan(Double.NaN));
        Assert.expectThrows(IllegalArgumentException.class, () -> HandLayoutStrategy.fan(61));
    }

    @Test public void battlefieldKeepsOversizedStacksApartAndMirrorsRows() {
        final var groups = List.of(new BattlefieldLayoutStrategy.Group(360, 250, BattlefieldLayoutStrategy.Kind.CREATURE),
                new BattlefieldLayoutStrategy.Group(120, 180, BattlefieldLayoutStrategy.Kind.CREATURE),
                new BattlefieldLayoutStrategy.Group(180, 210, BattlefieldLayoutStrategy.Kind.LAND),
                new BattlefieldLayoutStrategy.Group(180, 280, BattlefieldLayoutStrategy.Kind.OTHER));
        for (boolean opponent : new boolean[]{false, true}) {
            for (int width : new int[]{250, 800}) {
                final var positions = BattlefieldLayoutStrategy.LANES.arrange(groups, width, 400, opponent);
                for (int i = 0; i < groups.size(); i++) {
                    final var a = new java.awt.Rectangle(positions.get(i).x, positions.get(i).y, groups.get(i).width(), groups.get(i).height());
                    for (int j = 0; j < i; j++) {
                        Assert.assertFalse(a.intersects(new java.awt.Rectangle(positions.get(j).x, positions.get(j).y,
                                groups.get(j).width(), groups.get(j).height())));
                    }
                }
                Assert.assertEquals(positions.get(0).y < positions.get(2).y, !opponent);
            }
        }
    }
}
