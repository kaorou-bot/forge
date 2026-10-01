package forge.screens.match.layout;

import com.google.gson.JsonParser;
import com.google.common.collect.HashMultiset;
import forge.game.card.CounterType;
import forge.game.player.PlayerView;
import java.awt.Color;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.Assert;
import org.testng.annotations.Test;
import static org.mockito.Mockito.*;

public class SkinAuthoringProbeTest {
    @Test public void widthCapIsOptionalAndPreservesFanGeometry() {
        final var legacy = HandLayoutStrategy.fan(28, .8, 1, .2);
        Assert.assertEquals(legacy.arrange(7,3000,2000,500).get(0).width(),500, "Legacy Java providers retain their caller-controlled maximum");
        for (int w : new int[]{100, 640, 1920}) {
            for (int h : new int[]{60, 228, 600}) {
                Assert.assertEquals(legacy.arrange(7,w,h,300), HandLayoutStrategy.fan(28,.8,1,.2,300).arrange(7,w,h,300));
                final var capped = HandLayoutStrategy.fan(28,.8,1,.2,70).arrange(7,w,h,300);
                Assert.assertTrue(capped.stream().allMatch(p -> p.width() <= 70));
                Assert.assertTrue(HandLayoutStrategy.fan(28,.8,1,.2,70).arrange(7,w,h,50).stream().allMatch(p -> p.width() <= 50));
            }
        }
        final var parsed = MatchCardPresentation.read(JsonParser.parseString("{\"hand\":\"fan\",\"handCardWidthMax\":80}").getAsJsonObject(), true);
        Assert.assertEquals(parsed.hand().arrange(7,1000,600,300).get(0).width(),80);
        for (String bad : new String[]{"{\"handCardWidthMax\":80}","{\"hand\":\"fan\",\"handCardWidthMax\":15}","{\"hand\":\"fan\",\"handCardWidthMax\":80.5}"}) {
            Assert.expectThrows(IllegalArgumentException.class, () -> MatchCardPresentation.read(JsonParser.parseString(bad).getAsJsonObject(),true));
        }
        Assert.expectThrows(IllegalArgumentException.class, () -> MatchCardPresentation.read(JsonParser.parseString("{\"hand\":\"fan\",\"handCardWidthMax\":80}").getAsJsonObject(),false));
    }
    @Test public void errorsIdentifyFieldValueAndSupportedChoices() {
        final var e = Assert.expectThrows(IllegalArgumentException.class, () -> MatchCardPresentation.read(JsonParser.parseString("{\"battlefield\":\"typo\"}").getAsJsonObject(),true));
        Assert.assertTrue(e.getMessage().contains("cards.battlefield='typo'"));
        Assert.assertTrue(e.getMessage().contains("adaptive"));
    }
    @Test public void conditionalStatusUsesExactlyTheStatusCounterSource() {
        final var p = mock(PlayerView.class);
        Assert.assertFalse(MatchVisibility.STATUS_NONEMPTY.test(null,null));
        Assert.assertFalse(MatchVisibility.STATUS_NONEMPTY.test(null,p));
        final var counters = HashMultiset.<CounterType>create(); when(p.getCounters()).thenReturn(counters);
        Assert.assertFalse(MatchVisibility.STATUS_NONEMPTY.test(null,p));
        counters.add(CounterType.getType("POISON"),2);
        Assert.assertTrue(MatchVisibility.STATUS_NONEMPTY.test(null,p));
        counters.clear(); Assert.assertFalse(MatchVisibility.STATUS_NONEMPTY.test(null,p));
    }
    @Test public void conditionalStatusParserChecksScopeAndVersion() {
        final var config = JsonParser.parseString("""
                {"widgets":{"PHASES_ACTIVE":[0,0,1,.1],"FIELD_0.STATUS":[0,.2,1,.1]},
                 "visibility":{"FIELD_0.STATUS":"STATUS_NONEMPTY"}}
                """).getAsJsonObject();
        Assert.assertNotNull(MatchSceneLayout.read(config,null,true,true));
        Assert.expectThrows(IllegalArgumentException.class, () -> MatchSceneLayout.read(config,null,true,false));
        config.getAsJsonObject("visibility").addProperty("PHASES_ACTIVE","STATUS_NONEMPTY");
        Assert.expectThrows(IllegalArgumentException.class, () -> MatchSceneLayout.read(config,null,true,true));
    }
    @Test public void documentStyleFallsBackAndRestoresDynamicDescendants() throws Exception {
        final var theme = MatchSkinTheme.read(JsonParser.parseString("""
                {"styles":{"text":{"fontSize":14,"textColor":"#00FF00"},
                 "text.REPORT_LOG":{"fontSize":19,"textColor":"#FF0000"}}}
                """).getAsJsonObject(),null,true);
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            final var root = new javax.swing.JPanel(); final var text = new javax.swing.JTextArea("日志"); root.add(text);
            final Color before = text.getForeground();
            final var restore = theme.apply(root,"document.REPORT_LOG");
            Assert.assertEquals(text.getForeground(),Color.RED); Assert.assertEquals(text.getFont().getSize(),19);
            final var later = new javax.swing.JTextArea("新日志"); root.add(later); Assert.assertEquals(later.getForeground(),Color.RED);
            restore.run(); Assert.assertEquals(text.getForeground(),before);
            final var fallback = theme.apply(root,"document.REPORT_STACK");
            Assert.assertEquals(text.getForeground(),Color.GREEN); fallback.run();
            Assert.assertEquals(root.getContainerListeners().length,0);
        });
    }
    @Test public void referenceProbeDecodesBundledSkinAndWritesOnlyNewOutput() throws Exception {
        final var root = Files.createTempDirectory("forge-authoring-probe-test-");
        try {
            final var out = root.resolve("new-output");
            final var report = SkinAuthoringProbe.inspect(Path.of("../skins/dusk-sanctum"),out,1280,720);
            Assert.assertEquals(report.get("two_player_one_hand"),"scene");
            Assert.assertTrue(Files.size(out.resolve("geometry.png")) > 1000);
            Assert.assertTrue(Files.size(out.resolve("components.png")) > 1000);
            Assert.assertTrue(((java.util.List<?>)report.get("geometry")).size() >= 3);
            Assert.assertTrue(SkinAuthoringProbe.capabilities().getAsJsonArray("features").toString().contains("hand-width-cap"));
            Assert.expectThrows(IllegalArgumentException.class, () -> SkinAuthoringProbe.inspect(Path.of("../skins/dusk-sanctum"),out,1280,720));
        } finally {
            try (var paths = Files.walk(root)) { for (var p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) { Files.delete(p); } }
        }
    }
}
