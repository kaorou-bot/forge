package forge.screens.match.layout;

import com.google.gson.JsonParser;
import java.awt.Rectangle;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.testng.Assert;
import org.testng.annotations.Test;

public class MatchSkinV5Test {
    private static MatchUiLayout sample() throws Exception {
        final var root = Path.of("../skins/dusk-observatory");
        try (var reader = Files.newBufferedReader(root.resolve("match-ui.json"))) { return MatchUiLayout.read(reader,root); }
    }
    private static List<String> docs(int players, int hands) {
        final var docs = new ArrayList<>(List.of("REPORT_STACK","REPORT_MESSAGE","BUTTON_DOCK","CARD_PICTURE","CARD_DETAIL","REPORT_LOG","REPORT_COMBAT","REPORT_DEPENDENCIES","DEV_MODE"));
        for(int i=0;i<players;i++) { docs.add("FIELD_"+i); }
        for(int i=0;i<hands;i++) { docs.add("HAND_"+i); }
        return docs;
    }
    @Test public void exampleHasRealScenesForTwoThroughEightPlayers() throws Exception {
        final var root=sample();
        for(int players=2;players<=8;players++) { for(int width : List.of(1280,1920,2560)) {
            final var choice=root.experience().choose(root,width,players,"AUTO");
            Assert.assertEquals(choice.id(),players==2 ? width<=1440 ? "compact":"base" : "players-"+players);
            for(int hands=0;hands<=1;hands++) {
                Assert.assertTrue(choice.layout().scene().supports(docs(players,hands)),choice.id());
                choice.layout().arrange(docs(players,hands));
            }
            Assert.assertFalse(choice.layout().scene().supports(docs(players,2)));
        } }
    }
    @Test public void compactOverrideDoesNotReplaceMultiplayer() throws Exception {
        final var root=sample();
        Assert.assertEquals(root.experience().choose(root,2400,2,"COMPACT").id(),"compact");
        Assert.assertEquals(root.experience().choose(root,2400,6,"COMPACT").id(),"players-6");
    }

    @Test public void sidebarReservesReadableRulesAndReusesEmptyStackSpace() throws Exception {
        final var root = sample();
        for (int players = 2; players <= 8; players++) {
            final var layout = root.experience().choose(root, 1280, players, "AUTO").layout();
            final var detail = layout.arrange(docs(players, 1)).stream().filter(c -> c.documents().contains("CARD_DETAIL")).findFirst().orElseThrow();
            Assert.assertTrue(detail.bounds().h() * 720 >= 300, "Rules need body space after tabs and card headings");
            Assert.assertTrue(detail.documents().contains("CARD_PICTURE"));
            final var stack = layout.scene().floating().get("REPORT_STACK");
            final var log = layout.scene().floating().get("REPORT_LOG");
            Assert.assertEquals(stack.bounds(), log.bounds());
            Assert.assertEquals(stack.visibleWhen(), MatchVisibility.STACK_NONEMPTY);
            Assert.assertEquals(log.visibleWhen(), MatchVisibility.STACK_EMPTY);
            Assert.assertFalse(detail.bounds().overlaps(log.bounds()));
        }
        Assert.assertTrue(MatchVisibility.STACK_EMPTY.test(null, null));
        Assert.assertFalse(MatchVisibility.STACK_NONEMPTY.test(null, null));
    }
    @Test public void anchorAlwaysStaysInsideReservedSlot() {
        final var anchor=MatchAnchor.read(JsonParser.parseString("{\"minWidth\":100,\"maxWidth\":160,\"aspectRatio\":1,\"horizontal\":\"END\"}").getAsJsonObject());
        Assert.assertEquals(anchor.fit(new Rectangle(0,0,300,180)),new Rectangle(140,10,160,160));
        for(int w:List.of(1,10,80,400)) { final var slot=new Rectangle(7,9,w,50); final var r=anchor.fit(slot); Assert.assertTrue(slot.contains(r)); Assert.assertEquals(r.width,r.height); }
    }
    @Test public void settingsAndFloatingSurviveReloadAndReset() throws Exception {
        final var dir=Files.createTempDirectory("skin-v5-prefs-");
        final var prefs=new MatchSkinPreferences(dir,"sample",MatchSkinSettings.DEFAULT);
        final var settings=new MatchSkinSettings(1.2,125,.4,.8,"COMPACT"); prefs.settings(settings);
        final var state=new MatchSkinPreferences.Panel(new MatchUiLayout.Bounds(.1,.2,.3,.4),true,true);
        prefs.panel("base","REPORT_LOG",state);
        final var again=new MatchSkinPreferences(dir,"sample",MatchSkinSettings.DEFAULT);
        Assert.assertEquals(again.settings(),settings); Assert.assertEquals(again.panel("base","REPORT_LOG"),state);
        Assert.assertNull(again.panel("compact","REPORT_LOG"));
        again.reset(); Assert.assertEquals(again.settings(),MatchSkinSettings.DEFAULT);
        Assert.assertNull(again.panel("base","REPORT_LOG"));
    }
    @Test public void corruptPreferencesAreRecoverable() throws Exception {
        final var dir=Files.createTempDirectory("skin-v5-corrupt-"); Files.writeString(dir.resolve("sample.json"),"{bad");
        final var prefs=new MatchSkinPreferences(dir,"sample",MatchSkinSettings.DEFAULT);
        Assert.assertEquals(prefs.settings(),MatchSkinSettings.DEFAULT);
        Assert.assertEquals(Files.readString(dir.resolve("sample.invalid.json")),"{bad");
    }
    @Test public void invalidPresetIsRejectedBeforeAnyRendering() throws Exception {
        final var root=sample().experience().source();
        root.getAsJsonObject("experience").getAsJsonArray("variants").get(0).getAsJsonObject().addProperty("unexpected",1);
        Assert.expectThrows(IllegalArgumentException.class,()->MatchUiLayout.read(new StringReader(root.toString()),Path.of("../skins/dusk-observatory")));
    }
    @Test public void settingsNeverFadeTextOrMutateAuthorsStyles() throws Exception {
        final var theme=sample().scene().appearance();
        final var adjusted=theme.withSettings(new MatchSkinSettings(1.2,150,0,0,"AUTO"));
        Assert.assertEquals(adjusted.style("floating").visual().opacity(),0f);
        Assert.assertEquals(adjusted.style("text.REPORT_LOG").visual().textColor(),theme.style("text.REPORT_LOG").visual().textColor());
        Assert.assertTrue(theme.style("floating").visual().opacity()>0);
        Assert.assertEquals(adjusted.style("phase").fontSize(),theme.style("phase").fontSize()*1.2f,.001);
    }
    @Test public void polygonClipsCornersAndKeepsCenter() throws Exception {
        final var visual=sample().scene().appearance().style("avatar").visual();
        Assert.assertEquals(visual.shape(),MatchSkinVisual.ShapeKind.POLYGON);
        Assert.assertFalse(visual.outline(100,100,0).contains(1,1)); Assert.assertTrue(visual.outline(100,100,0).contains(50,50));
    }
    @Test public void editorReusesDecodedAssetsAndValidatesAllPresets() throws Exception {
        final var original = sample();
        final var json = original.experience().source();
        final var draft = MatchSkinEditor.geometryDraft(json, original);
        Assert.assertSame(draft.scene().appearance(), original.scene().appearance());
        Assert.assertSame(draft.experience().variants().get(0).layout().scene().appearance(), original.scene().appearance());
        Assert.assertEquals(draft.experience().source(), json);
        json.getAsJsonObject("scene").getAsJsonObject("widgets").add("PROMPT_OK",json.getAsJsonObject("scene").getAsJsonObject("widgets").get("PROMPT_CANCEL").deepCopy());
        Assert.expectThrows(IllegalArgumentException.class, () -> MatchSkinEditor.geometryDraft(json, original));
    }
    @Test public void multiplayerExampleReservesUsableBattlefieldHeight() throws Exception {
        final var root=sample();
        for(int players=3;players<=8;players++) {
            final var layout=root.experience().choose(root,1920,players,"AUTO").layout();
            for(var cell:layout.arrange(docs(players,1))) {
                if(cell.documents().size()==1 && cell.documents().get(0).startsWith("FIELD_")) {
                    Assert.assertTrue(cell.bounds().h()*1080>=140,"Battlefield too short: "+cell);
                }
            }
        }
    }
    @Test public void partitionedRearBandsNeverCrossDivider() {
        for(boolean right:List.of(false,true)) { for(double ratio:List.of(.3,.5,.7)) {
            final var layout=new AdaptiveBattlefieldLayout(true,8,6,ratio,right);
            final var groups=List.of(new BattlefieldLayoutStrategy.Group(70,95,BattlefieldLayoutStrategy.Kind.LAND),new BattlefieldLayoutStrategy.Group(70,95,BattlefieldLayoutStrategy.Kind.OTHER));
            final var positions=layout.arrange(groups,600,400,false);
            Assert.assertTrue(right ? positions.get(0).x>=600*ratio : positions.get(0).x+70<=600*ratio);
            Assert.assertTrue(right ? positions.get(1).x+70<=600*ratio : positions.get(1).x>=600*ratio);
        } }
    }
    @Test public void documentStyleRestoresMarginAndLinkedStyles() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(()->{
            final var text=new javax.swing.JEditorPane("text/html","<p>中文行一</p><p>中文行二</p>");
            final var before=text.getMargin(); final var doc=(javax.swing.text.html.HTMLDocument) text.getDocument();
            final int count=doc.getStyleSheet().getStyleSheets()==null?0:doc.getStyleSheet().getStyleSheets().length;
            final var restore=new MatchDocumentFormat(12,8,"LEFT").apply(text);
            Assert.assertEquals(text.getMargin().left,12);
            restore.run(); Assert.assertEquals(text.getMargin(),before);
            Assert.assertEquals(doc.getStyleSheet().getStyleSheets()==null?0:doc.getStyleSheet().getStyleSheets().length,count);
        });
    }
}
