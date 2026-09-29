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
        Assert.assertEquals(actual.size(), docs.size());
        Assert.assertEquals(new HashSet<>(actual), new HashSet<>(docs));
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
