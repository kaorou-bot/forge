package forge.screens.match.layout;

import com.google.gson.JsonParser;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.testng.Assert;
import org.testng.annotations.Test;
import static forge.screens.match.layout.BattlefieldLayoutStrategy.Kind.*;

public class AdaptiveBattlefieldLayoutTest {
    private static BattlefieldLayoutStrategy.Group group(int width, int height, BattlefieldLayoutStrategy.Kind kind) {
        return new BattlefieldLayoutStrategy.Group(width, height, kind);
    }

    @Test public void rearGroupsAreSideBySideAndOnlyTheVerticalOrderMirrors() {
        final var strategy = BattlefieldLayoutStrategy.adaptive(true, 8, 6);
        final var groups = List.of(group(100, 150, CREATURE), group(100, 150, LAND), group(100, 150, OTHER));
        for (boolean opponent : List.of(false, true)) {
            final var p = strategy.placements(groups, 800, 400, opponent);
            Assert.assertEquals(p.get(1).y(), p.get(2).y(), "Rear groups must share the same starting row");
            Assert.assertEquals(p.get(0).x() + 50, 400, "Creature remains centered in the entire battlefield");
            Assert.assertTrue(p.get(1).x() + p.get(1).width() <= 400);
            Assert.assertTrue(p.get(2).x() >= 400);
            Assert.assertEquals(p.get(0).y() < p.get(1).y(), !opponent);
            Assert.assertEquals(p.get(0).scale(), 1.0);
        }
    }

    @Test public void emptyHalfIsNeverBorrowedAndMissingCategoriesAreAllowed() {
        final var strategy = BattlefieldLayoutStrategy.adaptive(true, 8, 6);
        Assert.assertTrue(strategy.placements(List.of(), 800, 400, false).isEmpty());
        for (var kind : List.of(LAND, OTHER)) {
            final var groups = new ArrayList<BattlefieldLayoutStrategy.Group>();
            for (int i = 0; i < 25; i++) { groups.add(group(95, 150, kind)); }
            for (boolean opponent : List.of(false, true)) {
                verify(groups, strategy.placements(groups, 800, 400, opponent), 800);
            }
        }
    }

    @Test public void mixedWrappedAndOversizedStacksStayInsideTheirOwnHalf() {
        final var random = new Random(20261001);
        for (int width : new int[]{2, 15, 99, 241, 500, 799, 1200, 1920}) {
            for (int gap : new int[]{0, 6, 80}) {
                final var strategy = BattlefieldLayoutStrategy.adaptive(true, 8, gap);
                for (int count : new int[]{1, 2, 8, 40, 100}) {
                    final var groups = new ArrayList<BattlefieldLayoutStrategy.Group>();
                    for (int i = 0; i < count; i++) {
                        groups.add(group(30 + random.nextInt(900), 50 + random.nextInt(800), BattlefieldLayoutStrategy.Kind.values()[i % 3]));
                    }
                    for (boolean opponent : List.of(false, true)) {
                        final var actual = strategy.placements(groups, width, 300, opponent);
                        verify(groups, actual, width);
                        Assert.assertEquals(actual, strategy.placements(groups, width, 300, opponent), "No viewport-dependent oscillation");
                    }
                }
            }
        }
    }

    @Test public void oversizedAttachmentStacksScaleTheirCardCoordinatesAndTappedFootprintTogether() {
        final var strategy = BattlefieldLayoutStrategy.adaptive(true, 8, 6);
        for (int count : new int[]{1, 4, 40, 300}) {
            final int groupWidth = 144 + (count - 1) * 12;
            final int groupHeight = 148 + (count - 1) * 17;
            for (int width : new int[]{50, 241, 800}) {
                for (var kind : List.of(LAND, OTHER)) {
                    final var p = strategy.placements(List.of(group(groupWidth, groupHeight, kind)), width, 400, false).get(0);
                    for (int i = 0; i < count; i++) {
                        final var card = p.cardBounds(i * 12, i * 17, 100, 140);
                        // At 90 degrees the rightmost face edge is x + upright height, not x + upright width.
                        Assert.assertTrue(card.x + card.height <= p.x() + p.width(), "Tapped card crosses its fitted stack boundary");
                        Assert.assertTrue(card.y + card.height <= p.y() + p.height());
                        Assert.assertTrue(card.width >= 1 && card.height >= 1);
                    }
                }
            }
        }
    }

    @Test public void resizingRecoversOriginalScaleAndKeepsOpponentCreaturesInFront() {
        final var strategy = BattlefieldLayoutStrategy.adaptive(true, 8, 6);
        final var groups = List.of(group(150, 210, LAND), group(150, 210, LAND), group(150, 210, LAND), group(100, 140, CREATURE));
        final var narrow = strategy.placements(groups, 200, 240, true);
        Assert.assertTrue(narrow.get(0).scale() < 1);
        verify(groups, narrow, 200);
        Assert.assertTrue(narrow.get(3).y() + narrow.get(3).height() >= narrow.get(2).y() + narrow.get(2).height() + 8,
                "The front band's bottom remains nearer the opponent, even when disjoint bands share vertical space");
        final var wide = strategy.placements(groups, 1600, 800, true);
        Assert.assertEquals(wide.get(0).scale(), 1.0);
        Assert.assertEquals(wide.get(0).y(), wide.get(2).y());
        Assert.assertEquals(groups.get(0).width(), 150, "Source dimensions must not shrink cumulatively");
    }

    @Test public void startAlignmentAndGapValidationMatchExistingControls() {
        final var groups = List.of(group(100, 140, CREATURE));
        Assert.assertEquals(BattlefieldLayoutStrategy.adaptive(false, 8, 6).placements(groups, 800, 400, false).get(0).x(), 4);
        Assert.expectThrows(IllegalArgumentException.class, () -> BattlefieldLayoutStrategy.adaptive(true, -1, 6));
        Assert.expectThrows(IllegalArgumentException.class, () -> BattlefieldLayoutStrategy.adaptive(true, 8, 81));
    }

    @Test public void sparseRowsUseContentHeightInsteadOfHalfTheViewport() {
        final var strategy = BattlefieldLayoutStrategy.adaptive(true, 8, 6);
        final var groups = List.of(group(100, 120, CREATURE), group(110, 120, LAND), group(100, 120, OTHER));
        for (boolean opponent : List.of(false, true)) {
            for (int height : new int[]{256, 400, 800}) {
                final var p = strategy.placements(groups, 1200, height, opponent);
                final var first = p.get(opponent ? 1 : 0);
                final var second = p.get(opponent ? 0 : 1);
                Assert.assertEquals(second.y() - first.y() - first.height(), 8, "No artificial empty half-screen gap");
                Assert.assertTrue(p.stream().allMatch(a -> a.y() + a.height() <= height - 4));
                Assert.assertEquals(p.get(0).scale(), 1.0);
                Assert.assertEquals(opponent ? p.get(0).y() + p.get(0).height() : p.get(0).y(), opponent ? height - 4 : 4);
            }
        }
    }

    @Test public void missingFrontBandDoesNotReserveAnEmptyRow() {
        final var strategy = BattlefieldLayoutStrategy.adaptive(true, 8, 6);
        final var p = strategy.placements(List.of(group(100, 120, LAND), group(100, 120, OTHER)), 800, 200, false);
        Assert.assertEquals(p.get(0).y(), 4);
        Assert.assertEquals(p.get(1).y(), 4);
    }

    @Test public void rotationAndAttachmentsFitShortFieldsWithBoundedScaling() {
        final var strategy = BattlefieldLayoutStrategy.adaptive(true, 8, 6);
        final var groups = List.of(group(174, 174, CREATURE), group(194, 200, LAND), group(174, 174, OTHER));
        for (boolean opponent : List.of(false, true)) {
            final var p = strategy.placements(groups, 1200, 265, opponent);
            verify(groups, p, 1200);
            Assert.assertTrue(p.stream().allMatch(a -> a.y() + a.height() <= 261));
            Assert.assertTrue(p.stream().allMatch(a -> a.scale() == 1), "Disjoint sparse groups fit at full size, without mandatory shrinking");
            Assert.assertEquals(p, strategy.placements(groups, 1200, 265, opponent));
        }
        final var many = new ArrayList<BattlefieldLayoutStrategy.Group>();
        for (int i = 0; i < 50; i++) { many.add(group(100, 140, LAND)); }
        final var crowded = strategy.placements(many, 400, 240, false);
        Assert.assertTrue(crowded.stream().allMatch(p -> p.scale() >= .65));
        Assert.assertTrue(crowded.stream().anyMatch(p -> p.y() + p.height() > 240), "Crowded boards remain scrollable");
    }

    @Test public void adaptiveRequiresV4AndExistingModesAreUnchanged() {
        final var json = JsonParser.parseString("{\"battlefield\":\"adaptive\",\"battlefieldAlign\":\"CENTER\"}").getAsJsonObject();
        Assert.assertTrue(MatchCardPresentation.read(json, true).battlefield() instanceof AdaptiveBattlefieldLayout);
        Assert.expectThrows(IllegalArgumentException.class, () -> MatchCardPresentation.read(json, false));
        json.addProperty("battlefield", "ADAPTIVE");
        Assert.expectThrows(IllegalArgumentException.class, () -> MatchCardPresentation.read(json, true));
        json.remove("battlefieldAlign");
        json.addProperty("battlefield", "lanes");
        Assert.assertSame(MatchCardPresentation.read(json, false).battlefield(), BattlefieldLayoutStrategy.LANES);
        final var groups = List.of(group(1000, 800, CREATURE), group(200, 300, LAND), group(350, 300, OTHER));
        for (var legacy : List.of(BattlefieldLayoutStrategy.LANES, MatchCardPresentation.read(json, true).battlefield())) {
            Assert.assertFalse(legacy.sizesFromContent(), "Classic sizing must not change for older layout strategies");
            final var points = legacy.arrange(groups, 400, 300, false);
            final var placements = legacy.placements(groups, 400, 300, false);
            for (int i = 0; i < groups.size(); i++) {
                Assert.assertEquals(placements.get(i).scale(), 1.0);
                Assert.assertEquals(placements.get(i).x(), points.get(i).x);
                Assert.assertEquals(placements.get(i).y(), points.get(i).y);
                Assert.assertEquals(placements.get(i).width(), groups.get(i).width());
            }
        }
        json.addProperty("battlefield", "classic");
        Assert.assertNull(MatchCardPresentation.read(json, true).battlefield());
    }

    @Test public void builtinSampleUsesTheNewRule() throws Exception {
        Assert.assertTrue(BuiltinMatchSkin.loadDuskSanctum().cards().battlefield() instanceof AdaptiveBattlefieldLayout);
    }

    private static void verify(List<BattlefieldLayoutStrategy.Group> groups, List<BattlefieldLayoutStrategy.Placement> actual, int width) {
        Assert.assertEquals(actual.size(), groups.size());
        for (int i = 0; i < groups.size(); i++) {
            final var a = actual.get(i);
            Assert.assertTrue(a.x() >= 0 && a.x() + a.width() <= width);
            Assert.assertTrue(a.y() >= 0 && a.width() > 0 && a.height() > 0 && a.scale() > 0 && a.scale() <= 1);
            if (groups.get(i).kind() == LAND) { Assert.assertTrue(a.x() + a.width() <= width / 2); }
            if (groups.get(i).kind() == OTHER) { Assert.assertTrue(a.x() >= width / 2); }
            for (int j = 0; j < i; j++) {
                final var b = actual.get(j);
                Assert.assertFalse(new Rectangle(a.x(), a.y(), a.width(), a.height()).intersects(new Rectangle(b.x(), b.y(), b.width(), b.height())), "Stacks must never overlap");
            }
        }
    }
}
