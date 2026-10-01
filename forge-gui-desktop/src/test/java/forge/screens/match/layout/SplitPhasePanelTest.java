package forge.screens.match.layout;

import com.google.gson.JsonParser;
import forge.game.phase.PhaseType;
import forge.toolbox.special.PhaseIndicator;
import forge.toolbox.special.PhaseLabel;
import forge.util.Localizer;
import java.awt.Color;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class SplitPhasePanelTest {
    @BeforeClass public void initializeLanguage() {
        Localizer.getInstance().initialize("en-US", "../forge-gui/res/languages");
    }

    @Test public void halvesKeepOriginalPlayerCallbacksStatesAndOwnershipAcrossReloads() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final var upper = new PhaseIndicator();
            final var lower = new PhaseIndicator();
            final var top = upper.getLabelFor(PhaseType.MAIN1);
            final var bottom = lower.getLabelFor(PhaseType.MAIN1);
            final var topToggles = new AtomicInteger();
            final var bottomToggles = new AtomicInteger();
            final var topYields = new AtomicInteger();
            final var bottomYields = new AtomicInteger();
            top.setOnToggled(topToggles::incrementAndGet);
            bottom.setOnToggled(bottomToggles::incrementAndGet);
            top.setOnRightClick(() -> { topYields.incrementAndGet(); top.setYieldMarked(!top.isYieldMarked()); });
            bottom.setOnRightClick(() -> { bottomYields.incrementAndGet(); bottom.setYieldMarked(!bottom.isYieldMarked()); });
            final String originalTooltip = top.getToolTipText();
            final int listenerCount = top.getMouseListeners().length;
            for (int cycle = 0; cycle < 3; cycle++) {
                // Exercise both classic and scene restoration, without duplicating a listener.
                upper.setHorizontal(cycle % 2 == 0);
                lower.setHorizontal(cycle % 2 == 0);
                final String originalText = top.getText();
                final var panel = new SplitPhasePanel(upper, "<html>Opponent", "Opp.", lower, "Local", "Self");
                panel.setSize(824, 46);
                layoutTree(panel);
                Assert.assertSame(top, upper.getLabelFor(PhaseType.MAIN1));
                Assert.assertSame(top.getParent(), bottom.getParent());
                Assert.assertEquals(top.getY(), 0);
                Assert.assertEquals(top.getHeight(), 23);
                Assert.assertEquals(bottom.getY(), top.getHeight());
                Assert.assertEquals(top.getClientProperty(PhaseLabel.SPLIT_HALF_PROPERTY), PhaseLabel.SplitHalf.TOP);
                Assert.assertEquals(bottom.getClientProperty(PhaseLabel.SPLIT_HALF_PROPERTY), PhaseLabel.SplitHalf.BOTTOM);
                Assert.assertTrue(top.getToolTipText().contains("<html>Opponent"));
                Assert.assertFalse(top.getToolTipText().startsWith("<html>"), "Player names must not become HTML");
                for (PhaseType phase : PhaseType.values()) {
                    if (upper.getLabelFor(phase) == null) { continue; }
                    final var a = upper.getLabelFor(phase);
                    final var b = lower.getLabelFor(phase);
                    Assert.assertNotSame(a, b);
                    Assert.assertSame(a.getParent(), b.getParent());
                    Assert.assertEquals(a.getParent().getComponentCount(), 2);
                }
                top.setEnabled(true); bottom.setEnabled(false);
                top.setActive(true); bottom.setActive(false);
                click(panel, top, MouseEvent.BUTTON1);
                Assert.assertFalse(top.getEnabled()); Assert.assertFalse(bottom.getEnabled());
                Assert.assertEquals(topToggles.get(), cycle + 1);
                Assert.assertEquals(bottomToggles.get(), cycle);
                click(panel, bottom, MouseEvent.BUTTON1);
                Assert.assertFalse(top.getEnabled()); Assert.assertTrue(bottom.getEnabled());
                Assert.assertEquals(bottomToggles.get(), cycle + 1);
                final boolean wasTopYield = top.isYieldMarked(), wasBottomYield = bottom.isYieldMarked();
                click(panel, top, MouseEvent.BUTTON3);
                Assert.assertEquals(top.isYieldMarked(), !wasTopYield);
                Assert.assertEquals(bottom.isYieldMarked(), wasBottomYield);
                click(panel, bottom, MouseEvent.BUTTON3);
                Assert.assertEquals(bottom.isYieldMarked(), !wasBottomYield);
                Assert.assertFalse(top.getEnabled()); Assert.assertTrue(bottom.getEnabled());
                Assert.assertEquals(topYields.get(), cycle + 1); Assert.assertEquals(bottomYields.get(), cycle + 1);
                upper.resetPhaseButtons(); lower.resetPhaseButtons(); bottom.setActive(true);
                Assert.assertFalse(top.getActive()); Assert.assertTrue(bottom.getActive());
                panel.dispose(); panel.dispose(); // idempotent
                Assert.assertSame(top.getParent(), upper); Assert.assertSame(bottom.getParent(), lower);
                Assert.assertEquals(top.getText(), originalText);
                Assert.assertEquals(top.getToolTipText(), originalTooltip);
                Assert.assertNull(top.getClientProperty(PhaseLabel.SPLIT_HALF_PROPERTY));
                Assert.assertNull(bottom.getClientProperty(PhaseLabel.SPLIT_HALF_PROPERTY));
                Assert.assertEquals(top.getMouseListeners().length, listenerCount);
                Assert.assertFalse(top.getEnabled()); Assert.assertTrue(bottom.getEnabled());
                Assert.assertEquals(top.isYieldMarked(), !wasTopYield);
                Assert.assertTrue(bottom.getActive());
            }
        });
    }

    @Test public void ownershipCaptionsDoNotCallSpectatorsSelfOrFollowPriority() {
        Assert.assertEquals(SplitPhasePanel.ownerCaption(true, false, false), "Self");
        Assert.assertEquals(SplitPhasePanel.ownerCaption(false, true, true), "Opp.");
        Assert.assertEquals(SplitPhasePanel.ownerCaption(false, false, true), "Top");
        Assert.assertEquals(SplitPhasePanel.ownerCaption(false, false, false), "Bottom");
        Assert.assertEquals(SplitPhasePanel.ownerCaption(true, true, true), "Top");
    }

    @Test public void rendererGatesAndMultiplayerFallback() throws Exception {
        final String json = """
                {"widgets":{"PHASES_ACTIVE":[0,0,1,.1]},"renderers":{"PHASES_ACTIVE":"PHASES_SPLIT"}}
                """;
        final var definition = JsonParser.parseString(json).getAsJsonObject();
        Assert.assertTrue(MatchSceneLayout.read(definition, null, true, true).splitPhases());
        Assert.expectThrows(IllegalArgumentException.class, () -> MatchSceneLayout.read(definition, null, true, false));
        Assert.expectThrows(IllegalArgumentException.class, () -> MatchSceneLayout.read(definition, null, false, false));
        Assert.expectThrows(IllegalArgumentException.class, () -> MatchSceneLayout.read(
                JsonParser.parseString(json.replace("PHASES_SPLIT", "ZONE_BUTTON")).getAsJsonObject(), null, true, true));
        final var builtin = BuiltinMatchSkin.loadDuskSanctum().scene();
        Assert.assertTrue(builtin.splitPhases());
        Assert.assertTrue(builtin.supports(List.of("FIELD_0", "FIELD_1", "HAND_0")));
        for (var documents : List.of(List.of("FIELD_0"), List.of("FIELD_0", "FIELD_1", "FIELD_2"),
                List.of("FIELD_0", "FIELD_1", "HAND_0", "HAND_1"))) {
            Assert.assertFalse(builtin.supports(documents));
        }
        Assert.assertFalse(new MatchSceneLayout(builtin.widgets(), builtin.surface(), Map.of(), Map.of()).splitPhases(),
                "Existing scenes opt out unless they explicitly select the new renderer");
    }

    @Test public void highlightOnlyColorsActiveHalfAndRenderingRestoresTheme() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final var theme = MatchSkinTheme.read(JsonParser.parseString("""
                    {"text":"#FFFFFF","styles":{"phase":{"fill":"#102030","border":"#334455",
                     "radius":10,"fontSize":12,"states":{"selected":{"fill":"#805020"}}}}}
                    """).getAsJsonObject(), null, true);
            final var upper = new PhaseIndicator(); final var lower = new PhaseIndicator();
            final var top = upper.getLabelFor(PhaseType.MAIN1); final var bottom = lower.getLabelFor(PhaseType.MAIN1);
            final var oldFont = top.getFont();
            final var panel = new SplitPhasePanel(upper, "Opponent", "Opp.", lower, "Local", "Self");
            final var restore = theme.apply(panel, "PHASES_ACTIVE");
            try {
                panel.setSize(824, 46); layoutTree(panel);
                top.setActive(true); bottom.setActive(false); bottom.setYieldMarked(true);
                var pixels = render(panel);
                Assert.assertEquals(pixel(panel, top, pixels), new Color(0x805020).getRGB());
                Assert.assertEquals(pixel(panel, bottom, pixels), new Color(0x102030).getRGB(), "Yield is not the active phase");
                top.setActive(false); bottom.setActive(true);
                pixels = render(panel);
                Assert.assertEquals(pixel(panel, top, pixels), new Color(0x102030).getRGB());
                Assert.assertEquals(pixel(panel, bottom, pixels), new Color(0x805020).getRGB());
                Assert.assertEquals(pixels.getRGB(SwingUtilities.convertPoint(top, 0, top.getHeight() - 2, panel).x,
                        SwingUtilities.convertPoint(top, 0, top.getHeight() - 2, panel).y) >>> 24, 255,
                        "The inner corner is square: halves form one chip");
            } finally { restore.run(); panel.dispose(); }
            Assert.assertEquals(top.getFont(), oldFont);
            Assert.assertNull(top.getClientProperty(MatchSkinTheme.STYLE_PROPERTY));
        });
    }

    @Test public void builtinCaptionsFitMinimumPreviewSize() throws Exception {
        final var theme = BuiltinMatchSkin.loadDuskSanctum().scene().appearance();
        SwingUtilities.invokeAndWait(() -> {
            Localizer.getInstance().initialize("zh-CN", "../forge-gui/res/languages");
            final var upper = new PhaseIndicator(); final var lower = new PhaseIndicator();
            final var panel = new SplitPhasePanel(upper, "对手", "对手", lower, "自己", "自己");
            final var restore = theme.apply(panel, "PHASES_ACTIVE");
            try {
                panel.setSize(819, 46); layoutTree(panel); // 1280x720 at 64% x 6.4%
                for (PhaseLabel label : upper.allLabels()) {
                    Assert.assertEquals(theme.font(12).canDisplayUpTo(label.getText()), -1);
                    Assert.assertTrue(label.getFontMetrics(label.getFont()).stringWidth(label.getText()) <= label.getWidth() - 14,
                            "Caption needs room for stop/yield indicator: " + label.getText());
                    Assert.assertTrue(label.getFontMetrics(label.getFont()).getHeight() <= label.getHeight());
                }
                upper.getLabelFor(PhaseType.MAIN1).setActive(true);
                lower.getLabelFor(PhaseType.COMBAT_BEGIN).setEnabled(false);
                lower.getLabelFor(PhaseType.MAIN2).setYieldMarked(true);
                final String preview = System.getProperty("forge.splitPhasePreview");
                if (preview != null) {
                    try { ImageIO.write(render(panel), "png", Path.of(preview).toFile()); }
                    catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                }
            } finally {
                restore.run(); panel.dispose();
                Localizer.getInstance().initialize("en-US", "../forge-gui/res/languages");
            }
        });
    }

    private static void click(SplitPhasePanel panel, PhaseLabel label, int button) {
        final var point = SwingUtilities.convertPoint(label, label.getWidth() / 2, label.getHeight() / 2, panel);
        final var target = SwingUtilities.getDeepestComponentAt(panel, point.x, point.y);
        Assert.assertSame(target, label, "Hit testing must reach only the chosen player's original label");
        target.dispatchEvent(new MouseEvent(target, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                0, label.getWidth() / 2, label.getHeight() / 2, 1, false, button));
    }
    private static int pixel(SplitPhasePanel panel, PhaseLabel label, BufferedImage image) {
        final var p = SwingUtilities.convertPoint(label, 8, 4, panel);
        return image.getRGB(p.x, p.y);
    }
    private static void layoutTree(Container root) {
        root.invalidate(); root.doLayout();
        for (var child : root.getComponents()) { if (child instanceof Container c) { layoutTree(c); } }
    }
    private static BufferedImage render(SplitPhasePanel panel) {
        final var result = new BufferedImage(panel.getWidth(), panel.getHeight(), BufferedImage.TYPE_INT_ARGB);
        final var g = result.createGraphics();
        try { panel.paint(g); } finally { g.dispose(); }
        return result;
    }
}
