package forge.screens.match.layout;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.testng.Assert;
import org.testng.annotations.Test;

public class BuiltinMatchSkinTest {
    @Test public void menuReplacesRetiredPreviewsAndMigratesOldSelections() throws Exception {
        final var ids = DesktopMatchUi.providers().stream().map(DesktopMatchUi.Provider::id).toList();
        Assert.assertTrue(ids.containsAll(List.of("classic", "dusk-sanctum", "package", "skin")));
        Assert.assertFalse(ids.contains("arena"));
        Assert.assertFalse(ids.contains("tabletop"));
        for (String id : List.of("arena", "tabletop", "unknown")) {
            Assert.assertEquals(DesktopMatchUi.supportedSelection(id), "classic");
        }
        Assert.assertEquals(DesktopMatchUi.supportedSelection("dusk-sanctum"), "dusk-sanctum");
        final var provider = DesktopMatchUi.providers().stream().filter(p -> p.id().equals("dusk-sanctum")).findFirst().orElseThrow();
        Assert.assertEquals(provider.label(), "lblDesktopMatchUiDuskSanctum");
        Assert.assertEquals(provider.implementation().load().id(), "dusk-sanctum");
    }

    @Test public void bundledResourcesDecodeAndRemainReadable() throws Exception {
        verifyAssets(BuiltinMatchSkin.loadDuskSanctum());
    }

    @Test public void jarResourcesFullyLoadBeforeArchiveCloses() throws Exception {
        final var jar = Files.createTempFile("forge-builtin-skin-", ".jar");
        final var source = Path.of("../skins/dusk-sanctum");
        try {
            try (var zip = new ZipOutputStream(Files.newOutputStream(jar)); var paths = Files.walk(source)) {
                for (Path path : paths.filter(Files::isRegularFile).toList()) {
                    zip.putNextEntry(new ZipEntry("match-ui/dusk-sanctum/" + source.relativize(path).toString().replace('\\', '/')));
                    Files.copy(path, zip);
                    zip.closeEntry();
                }
            }
            final var url = URI.create("jar:" + jar.toUri() + "!/match-ui/dusk-sanctum/match-ui.json").toURL();
            // Loading twice checks that the ZIP filesystem is not leaked or cached open.
            final var first = BuiltinMatchSkin.read(url);
            final var second = BuiltinMatchSkin.read(url);
            Files.delete(jar);
            verifyAssets(first);
            verifyAssets(second);
        } finally {
            Files.deleteIfExists(jar);
        }
    }

    @Test public void builtinPreservesDocumentsAndCapacityFallback() throws Exception {
        final var layout = BuiltinMatchSkin.loadDuskSanctum();
        for (boolean developer : List.of(false, true)) {
            for (String hand : List.of("", "HAND_0", "HAND_6")) {
                final var docs = new ArrayList<>(List.of("FIELD_0", "FIELD_1", "REPORT_STACK", "REPORT_MESSAGE",
                        "REPORT_LOG", "REPORT_COMBAT", "REPORT_DEPENDENCIES", "BUTTON_DOCK", "CARD_PICTURE", "CARD_DETAIL"));
                if (!hand.isEmpty()) { docs.add(hand); }
                if (developer) { docs.add("DEV_MODE"); }
                Assert.assertTrue(layout.scene().supports(docs));
                final var represented = new ArrayList<>(layout.arrange(docs).stream().flatMap(c -> c.documents().stream()).toList());
                docs.stream().filter(layout.scene()::replacesDocument).forEach(represented::add);
                Assert.assertEquals(represented.size(), docs.size(), "No duplicated or missing document");
                Assert.assertEquals(new HashSet<>(represented), new HashSet<>(docs));
                for (int player = 2; player < 8; player++) {
                    docs.add("FIELD_" + player);
                    Assert.assertFalse(layout.scene().supports(docs), "Unsupported player count must use capacity-safe fallback");
                }
            }
        }
        Assert.assertFalse(layout.scene().supports(List.of("FIELD_0", "FIELD_1", "HAND_0", "HAND_1")));
    }

    @Test public void sampleKeepsActionsHandAndFloatingDetailSeparate() throws Exception {
        final var layout = BuiltinMatchSkin.loadDuskSanctum();
        final var scene = layout.scene();
        Assert.assertEquals(scene.floating().get("REPORT_STACK").visibleWhen(), MatchVisibility.STACK_NONEMPTY);
        Assert.assertTrue(scene.floating().containsKey("CARD_DETAIL"));
        Assert.assertTrue(scene.widgets().get("FIELD_0.NAME").w() >= .15);
        Assert.assertTrue(scene.widgets().get("FIELD_0.LIFE").w() >= .13);
        Assert.assertTrue(scene.widgets().get("PROMPT_OK").w() >= .11);
        final var hands = layout.regions().stream().filter(r -> r.documents().contains("hands")).findFirst().orElseThrow().bounds();
        Assert.assertTrue(hands.y() + hands.h() < scene.widgets().get("PROMPT_MESSAGE").y());
        Assert.assertNotNull(layout.cards().hand());
        Assert.assertNotNull(layout.cards().battlefield());
    }

    private static void verifyAssets(MatchUiLayout layout) {
        final var theme = layout.scene().appearance();
        Assert.assertNotNull(theme.background());
        Assert.assertEquals(theme.font(16).canDisplayUpTo("暮辉秘境 生物 牌库 坟墓场 放逐区 确认"), -1);
        Assert.assertEquals(theme.style("FIELD_0.AVATAR_IMAGE").visual().shape(), MatchSkinVisual.ShapeKind.HEXAGON);
        Assert.assertEquals(theme.style("floating.CARD_DETAIL").fill().getAlpha(), 0);
        Assert.assertEquals(theme.style("floating.REPORT_STACK").fill().getAlpha(), 0);
        Assert.assertEquals(theme.style("text").visual().textColor(), new Color(245, 248, 250));
        Assert.assertNotEquals(theme.style("PROMPT_OK").state(false, false, false, false).fill(), theme.style("PROMPT_OK").fill());
        for (int player : List.of(0, 1)) {
            for (String zone : List.of("LIBRARY", "GRAVEYARD", "EXILE")) {
                final var style = theme.style("FIELD_" + player + ".ZONE_" + zone);
                Assert.assertNotNull(style.visual().icon());
                final var image = new BufferedImage(200, 32, BufferedImage.TYPE_INT_ARGB);
                final var g = image.createGraphics();
                try {
                    g.setColor(Color.WHITE); g.setFont(theme.font(style.fontSize()));
                    style.paint(g, 200, 32, false, false);
                    style.visual().paintContent(g, 200, 32, "牌库 · 48", true);
                } finally { g.dispose(); }
                Assert.assertTrue((image.getRGB(100, 16) >>> 24) > 0);
            }
        }
    }
}
