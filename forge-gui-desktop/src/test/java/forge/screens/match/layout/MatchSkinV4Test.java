package forge.screens.match.layout;

import com.google.gson.JsonParser;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JTextArea;
import org.testng.Assert;
import org.testng.annotations.Test;

public class MatchSkinV4Test {
    @org.testng.annotations.BeforeClass public void initializeSkinPalette() throws Exception {
        forge.util.Localizer.getInstance().initialize("en-US", Path.of("../forge-gui/res/languages").toString());
        // The desktop normally initializes these before constructing any FLabel.
        // Initialize only its swatches, without starting a desktop window or changing user preferences.
        final var sprite=forge.toolbox.FSkin.class.getDeclaredField("bimPreferredSprite");
        sprite.setAccessible(true);
        final var font=forge.toolbox.FSkin.SkinFont.class.getDeclaredField("baseFont");
        font.setAccessible(true);
        if(font.get(null)==null) { font.set(null,new java.awt.Font(java.awt.Font.SANS_SERIF,java.awt.Font.PLAIN,14)); }
        final Object previous=sprite.get(null);
        try {
            if(previous==null) { sprite.set(null,solid(2048,2048,Color.GRAY)); }
            forge.toolbox.FSkin.Colors.updateAll();
        } finally { sprite.set(null,previous); }
    }
    private static MatchSkinTheme theme(String json, Path root) {
        return MatchSkinTheme.read(JsonParser.parseString(json).getAsJsonObject(), root, true);
    }
    private static BufferedImage canvas(int w, int h) { return new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB); }
    private static BufferedImage solid(int w, int h, Color color) {
        final var result = canvas(w, h); final var g = result.createGraphics();
        try { g.setColor(color); g.fillRect(0,0,w,h); } finally { g.dispose(); }
        return result;
    }

    @Test public void versionGatesAndLegacyCompatibility() throws Exception {
        final String json = """
            {"version":4,"id":"v4-test","regions":[{"bounds":[0,.1,1,.9],"documents":["remaining"]}],
             "scene":{"widgets":{"PHASES_ACTIVE":[0,0,1,.1]},"appearance":{"styles":{"button":{"shape":"HEXAGON"}}}},
             "cards":{"hand":"fan","handSpacing":0.6,"hoverLift":0.2,"battlefield":"lanes","battlefieldAlign":"CENTER"}}
            """;
        Assert.assertNotNull(MatchUiLayout.read(new StringReader(json)).scene().appearance());
        Assert.expectThrows(IllegalArgumentException.class, () -> MatchUiLayout.read(new StringReader(json.replace("\"version\":4", "\"version\":3"))));
        final String legacy = """
            {"version":3,"id":"legacy","regions":[{"bounds":[0,.1,1,.9],"documents":["remaining"]}],
             "scene":{"widgets":{"PHASES_ACTIVE":[0,0,1,.1]},"appearance":{"styles":{"button":{"fill":"#102030"}}}},
             "cards":{"hand":"fan","battlefield":"lanes"}}
            """;
        final var oldLayout = MatchUiLayout.read(new StringReader(legacy));
        Assert.assertNull(oldLayout.scene().appearance().style("PROMPT_OK").visual());
        Assert.assertSame(oldLayout.cards().battlefield(), BattlefieldLayoutStrategy.LANES);
        try (var reader = Files.newBufferedReader(Path.of("../skins/dusk-sanctum/match-ui.json"))) {
            Assert.assertNotNull(MatchUiLayout.read(reader, Path.of("../skins/dusk-sanctum")));
        }
    }

    @Test public void stateOverridesRetainDefaultsAndRejectRecursiveStates() {
        final var t = theme("""
            {"styles":{"button":{"fill":"#112233","fontSize":19,"highlight":"#FFFFFF",
            "states":{"hover":{"fill":"#FF0000"},"pressed":{"fill":"#00FF00"},"disabled":{"fill":"#0000FF"}}}}}
            """, null);
        final var s = t.style("PROMPT_OK");
        Assert.assertEquals(s.state(true,true,false,false).fill(), Color.RED);
        Assert.assertEquals(s.state(true,true,true,false).fill(), Color.GREEN);
        Assert.assertEquals(s.state(false,true,true,true).fill(), Color.BLUE);
        Assert.assertEquals(s.state(true,true,false,false).fontSize(),19f);
        Assert.assertNull(s.state(true,true,false,false).highlight());
        for (String bad : List.of("{\"states\":{\"hover\":{\"states\":{}}}}", "{\"opacity\":2}", "{\"shape\":\"STAR\"}", "{\"showText\":\"false\"}")) {
            Assert.expectThrows(IllegalArgumentException.class, () -> theme("{\"styles\":{\"button\":" + bad + "}}", null));
        }
    }

    @Test public void imageModesAndTinyNineSliceDoNotDistortCorners() {
        final var source=solid(6,6,Color.BLUE); final var sg=source.createGraphics();
        try {sg.setColor(Color.RED);sg.fillRect(0,0,2,2);} finally {sg.dispose();}
        final var nine=new MatchSkinTexture(source,MatchSkinTexture.Mode.NINE_SLICE,2,2,2,2);
        for(int size:List.of(1,3,6,40)) {
            final var image=canvas(size,size); final var g=image.createGraphics();
            try {nine.paint(g,0,0,size,size);} finally {g.dispose();}
            Assert.assertTrue((image.getRGB(0,0)>>>24)>0);
            if (size>=6) { Assert.assertEquals(image.getRGB(0,0),Color.RED.getRGB()); }
        }
        final var wide=solid(20,10,Color.RED);
        for(var mode:List.of(MatchSkinTexture.Mode.CONTAIN,MatchSkinTexture.Mode.COVER,MatchSkinTexture.Mode.TILE)) {
            final var image=canvas(40,40); final var g=image.createGraphics();
            try {new MatchSkinTexture(wide,mode,0,0,0,0).paint(g,0,0,40,40);} finally {g.dispose();}
            Assert.assertEquals(image.getRGB(20,20),Color.RED.getRGB());
            Assert.assertEquals(image.getRGB(0,0)>>>24,mode==MatchSkinTexture.Mode.CONTAIN?0:255);
        }
    }

    @Test public void shapesAndPanelAlphaKeepCornersClear() {
        for(String shape:List.of("CIRCLE","ELLIPSE","HEXAGON","DIAMOND")) {
            final var style=theme("{\"styles\":{\"avatar\":{\"shape\":\""+shape+"\",\"fill\":\"#FFFFFF\",\"opacity\":0.5}}}",null).style("FIELD_0.AVATAR_IMAGE");
            final var image=canvas(100,80);final var g=image.createGraphics();
            try {style.paint(g,100,80,false,false);} finally {g.dispose();}
            Assert.assertEquals(image.getRGB(0,0)>>>24,0);
            Assert.assertTrue(Math.abs((image.getRGB(50,40)>>>24)-128)<=1);
        }
    }

    @Test public void textureValidationUsesTheSharedAssetBudgetAndRejectsTraversal() throws Exception {
        final var root=Files.createTempDirectory("forge-v4-assets-");
        try {
            ImageIO.write(solid(8,8,Color.RED),"png",root.resolve("icon.png").toFile());
            final var t=theme("""
                {"background":{"path":"icon.png","mode":"TILE"},"styles":{"life":{"icon":{"path":"icon.png","mode":"CONTAIN"}}},
                 "decorations":[{"image":"icon.png","bounds":[0,0,1,1],"rotation":30,"opacity":0.5,"plane":"FOREGROUND"}]}
                """,root);
            Assert.assertSame(t.background(),t.style("FIELD_0.LIFE").visual().icon().image());
            Assert.assertEquals(t.decorations().size(),1);
            final var overlayTheme=theme("{\"decorations\":[{\"image\":\"icon.png\",\"bounds\":[0,0,1,1],\"plane\":\"FOREGROUND\"}]}",root);
            final var overlay=new MatchDecorationPanel(overlayTheme,true,List.of(new MatchUiLayout.Bounds(0,.7,1,.3)));
            overlay.setSize(100,100);
            final var rendered=canvas(100,100);final var overlayGraphics=rendered.createGraphics();
            try { overlay.paint(overlayGraphics); } finally { overlayGraphics.dispose(); }
            Assert.assertEquals(rendered.getRGB(50,50),Color.RED.getRGB());
            Assert.assertEquals(rendered.getRGB(50,90)>>>24,0,"Foreground art must not cover the protected hand");
            Assert.expectThrows(IllegalArgumentException.class,()->theme("{\"background\":{\"path\":\"icon.png\",\"mode\":\"NINE_SLICE\",\"slices\":[4,4,4,4]}}",root));
            Assert.expectThrows(IllegalArgumentException.class,()->theme("{\"styles\":{\"life\":{\"icon\":\"../outside.png\"}}}",root));
        } finally {Files.deleteIfExists(root.resolve("icon.png"));Files.deleteIfExists(root);}
    }

    @Test public void centeredCreaturesWrapWithoutBreakingOrOverlappingGroups() {
        final var strategy=BattlefieldLayoutStrategy.rows(true,8,6);
        for(boolean opponent:List.of(false,true)) {
            for(int count=1;count<=40;count++) {
                final var groups=new ArrayList<BattlefieldLayoutStrategy.Group>();
                for(int i=0;i<count;i++) {groups.add(new BattlefieldLayoutStrategy.Group(70+(i%3)*10,110,BattlefieldLayoutStrategy.Kind.CREATURE));}
                groups.add(new BattlefieldLayoutStrategy.Group(90,120,BattlefieldLayoutStrategy.Kind.LAND));
                groups.add(new BattlefieldLayoutStrategy.Group(130,120,BattlefieldLayoutStrategy.Kind.OTHER));
                final var points=strategy.arrange(groups,500,400,opponent);
                Assert.assertEquals(points.size(),groups.size());
                for(int i=0;i<groups.size();i++) {
                    final var r=new Rectangle(points.get(i).x,points.get(i).y,groups.get(i).width(),groups.get(i).height());
                    for(int j=0;j<i;j++) {Assert.assertFalse(r.intersects(new Rectangle(points.get(j).x,points.get(j).y,groups.get(j).width(),groups.get(j).height())));}
                }
                if(count==1) {Assert.assertEquals(points.get(0).x+35,250);}
            }
        }
    }

    @Test public void fanOptionsReserveRoomForLiftAndKeepRotatedCornersInside() {
        for(double lift:new double[]{0,.2,.4}) {
        final var fan=HandLayoutStrategy.fan(60,.6,1,lift);
        for(int count=1;count<=100;count++) {
            for(var p:fan.arrange(count,900,220,120)) {
                Assert.assertTrue(p.contains(p.x()+p.width()/2.0,p.y()+p.height()/2.0));
                for(double x:new double[]{-p.width()/2.0,p.width()/2.0}) {for(double y:new double[]{-p.height()/2.0,p.height()/2.0}) {
                    final var corner=p.corner(x,y);
                    Assert.assertTrue(corner.getX()>=0 && corner.getX()<=900);
                    Assert.assertTrue(corner.getY()-p.height()*fan.hoverLift()>=-1 && corner.getY()<=220);
                }}
            }
        }
        }
    }

    @Test public void decorativeOverlayNeverCapturesInputAndProtectsHand() {
        final var t=theme("{}",null);
        final var panel=new MatchDecorationPanel(t,true,List.of(new MatchUiLayout.Bounds(0,.7,1,.3)));
        panel.setBounds(0,0,200,100);
        Assert.assertFalse(panel.contains(10,10));
        Assert.assertFalse(panel.contains(100,90));
    }

    @Test public void translucentTextStylingRestoresOpaqueAndDynamicChildren() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(()->{
            final var t=theme("{\"styles\":{\"text\":{\"fill\":\"#00000080\",\"textColor\":\"#FFFFFF\"}}}",null);
            final var panel=new javax.swing.JPanel();final var first=new JTextArea("Rules");first.setOpaque(true);panel.add(first);
            final var restore=t.apply(panel,"document");Assert.assertFalse(first.isOpaque());
            final var late=new JTextArea("More rules");late.setOpaque(true);panel.add(late);Assert.assertFalse(late.isOpaque());
            restore.run();Assert.assertTrue(first.isOpaque());Assert.assertTrue(late.isOpaque());
            Assert.assertNull(first.getClientProperty(MatchSkinTheme.STYLE_PROPERTY));
        });
    }

    @Test public void actualLogBehindJLayerStaysTransparentAfterRefreshAndRestores() throws Exception {
        final var t = BuiltinMatchSkin.loadDuskSanctum().scene().appearance();
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            final var prefs = org.mockito.Mockito.mock(forge.localinstance.properties.ForgePreferences.class);
            try (var model = org.mockito.Mockito.mockStatic(forge.model.FModel.class)) {
                model.when(forge.model.FModel::getPreferences).thenReturn(prefs);
                final var body = new javax.swing.JPanel(new java.awt.BorderLayout());
                body.setSize(480, 240);
                final var restoreSurface = MatchSurfaceStyle.CLEAR.apply(body);
                final var restoreTheme = t.apply(body, "document");
                try {
                    // VLog.populate() is empty: its controller attaches a full log tree after styling.
                    final var log = new forge.screens.match.GameLogPanel();
                    log.addLogEntry("烤肉保留了7张牌的起手");
                    log.addLogEntry("回合 1：Vectomon 使用了芳林边陲");
                    final var originalOpacity = new java.util.IdentityHashMap<javax.swing.JComponent, Boolean>();
                    final var originalListeners = new java.util.IdentityHashMap<java.awt.Container, Integer>();
                    for (var c : descendants(log)) {
                        if (c instanceof javax.swing.JComponent jc) { originalOpacity.put(jc, jc.isOpaque()); }
                        if (c instanceof java.awt.Container container) { originalListeners.put(container, container.getContainerListeners().length); }
                    }
                    Assert.assertTrue(originalOpacity.entrySet().stream().anyMatch(e -> e.getValue()
                            && e.getKey().getClass().getSimpleName().equals("MyScrollablePanel")), "Exercise the real white background");
                    for (int i = 0; i < 3; i++) {
                        body.add(log, java.awt.BorderLayout.CENTER);
                        for (var c : originalOpacity.keySet()) {
                            if (c instanceof javax.swing.JPanel || c instanceof javax.swing.JScrollPane
                                    || c instanceof javax.swing.JViewport || c instanceof javax.swing.JLayer
                                    || c instanceof javax.swing.text.JTextComponent) {
                                Assert.assertFalse(c.isOpaque(), c.getClass().getSimpleName());
                            }
                        }
                        // Headless Swing has no validation event loop: settle viewport preferred sizes explicitly.
                        for (int pass = 0; pass < 3; pass++) { layoutTree(body); }
                        for (var c : descendants(log)) {
                            if (c instanceof javax.swing.text.JTextComponent) {
                                Assert.assertTrue(c.getWidth() > 400, "Log row must use the viewport width: " + c.getBounds());
                            }
                        }
                        final var pixels = canvas(480, 240); final var g = pixels.createGraphics();
                        try { body.paint(g); } finally { g.dispose(); }
                        Assert.assertEquals(pixels.getRGB(470, 220) >>> 24, 0, "Blank log area has no white backdrop");
                        int visibleText = 0;
                        for (int y = 0; y < 100; y++) { for (int x = 0; x < 460; x++) {
                            final int p = pixels.getRGB(x, y);
                            if ((p >>> 24) > 128 && (p & 255) > 200) { visibleText++; }
                        }}
                        Assert.assertTrue(visibleText > 100, "Real log glyphs stay bright and opaque");
                        final String preview = System.getProperty("forge.transparentLogPreview");
                        if (i == 0 && preview != null) {
                            try { ImageIO.write(pixels, "png", Path.of(preview).toFile()); }
                            catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                        }
                        body.remove(log);
                        originalOpacity.forEach((component, value) -> Assert.assertEquals(component.isOpaque(), value.booleanValue()));
                        originalListeners.forEach((component, value) -> Assert.assertEquals(component.getContainerListeners().length, value.intValue()));
                    }
                    body.add(log, java.awt.BorderLayout.CENTER);
                    log.addLogEntry("新增日志：请选择一张牌。");
                    for (var c : descendants(log)) {
                        if (c instanceof javax.swing.text.JTextComponent text) {
                            Assert.assertFalse(text.isOpaque());
                            Assert.assertEquals(text.getForeground(), new Color(245, 248, 250));
                        }
                    }
                    restoreTheme.run(); restoreSurface.run();
                    originalOpacity.forEach((component, value) -> Assert.assertEquals(component.isOpaque(), value.booleanValue()));
                    originalListeners.forEach((component, value) -> Assert.assertEquals(component.getContainerListeners().length, value.intValue()));
                } finally { restoreTheme.run(); restoreSurface.run(); }
            }
        });
    }

    @Test public void transparentSurfaceTracksNestedReplacementsWithoutChangingControls() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            final var root = new javax.swing.JPanel();
            final var restore = MatchSurfaceStyle.CLEAR.apply(root);
            final var content = new javax.swing.JPanel();
            final var button = new javax.swing.JButton("选择");
            final boolean buttonOpacity = button.isOpaque();
            final var buttonBorder = button.getBorder();
            content.add(button);
            final var scroll = new javax.swing.JScrollPane(content);
            final var layer = new javax.swing.JLayer<>(scroll);
            layer.setOpaque(true);
            root.add(layer);
            Assert.assertFalse(content.isOpaque()); Assert.assertFalse(layer.isOpaque());
            Assert.assertEquals(button.isOpaque(), buttonOpacity); Assert.assertSame(button.getBorder(), buttonBorder);
            final var replacement = new javax.swing.JPanel();
            scroll.setViewportView(replacement);
            Assert.assertTrue(content.isOpaque(), "Detached original view restores");
            Assert.assertFalse(replacement.isOpaque(), "Replacement view inherits transparent surface");
            restore.run();
            Assert.assertTrue(replacement.isOpaque()); Assert.assertTrue(layer.isOpaque());
            Assert.assertEquals(root.getContainerListeners().length, 0);
        });
    }

    private static java.util.List<java.awt.Component> descendants(java.awt.Component root) {
        final var result = new java.util.ArrayList<java.awt.Component>(); result.add(root);
        if (root instanceof java.awt.Container container) {
            for (var child : container.getComponents()) { result.addAll(descendants(child)); }
        }
        return result;
    }

    private static void layoutTree(java.awt.Container root) {
        root.invalidate();
        root.doLayout();
        for (var c : root.getComponents()) { if (c instanceof java.awt.Container container) { layoutTree(container); } }
    }

    @Test public void liveAvatarClipsWithoutLosingImageAndRestoresListeners() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            final var avatar = new forge.toolbox.FLabel(new forge.toolbox.FLabel.Builder().fontAlign(javax.swing.SwingConstants.CENTER).iconScaleFactor(1)) {
                void refreshIconForTest() { resetIcon(); }
            };
            avatar.setIcon(new javax.swing.ImageIcon(solid(80,80,Color.RED)));
            avatar.setSize(80,80);
            avatar.refreshIconForTest(); // The live widget does this after its resize/ancestor event.
            final int listeners = avatar.getMouseListeners().length;
            final var t = theme("{\"styles\":{\"avatar\":{\"shape\":\"CIRCLE\",\"textColor\":\"#FFFFFF\",\"padding\":0,\"states\":{\"hover\":{\"border\":\"#00FF00\"}}}}}",null);
            final var restore = t.apply(avatar,"FIELD_0.AVATAR_IMAGE");
            final var image = canvas(80,80); final var g = image.createGraphics();
            try { avatar.paint(g); } finally { g.dispose(); }
            Assert.assertEquals(image.getRGB(0,0)>>>24,0);
            Assert.assertEquals(image.getRGB(40,40),Color.RED.getRGB(),"Real player image remains visible even with text styling");
            avatar.dispatchEvent(new java.awt.event.MouseEvent(avatar,java.awt.event.MouseEvent.MOUSE_ENTERED,0,0,40,40,0,false));
            Assert.assertEquals(avatar.getClientProperty("forge.matchSkinHover"),true);
            restore.run();
            Assert.assertEquals(avatar.getMouseListeners().length,listeners);
            Assert.assertNull(avatar.getClientProperty("forge.matchSkinHover"));
        });
    }

    @Test public void phaseStyleFollowsLiveComponentsAndRetainsStopToggle() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            final var host=new javax.swing.JPanel();
            final var t=theme("{\"styles\":{\"phase\":{\"fill\":\"#112233\"},\"PHASE.MAIN1\":{\"fill\":\"#FF0000\"}}}",null);
            final var restore=t.apply(host,"PHASES_ACTIVE");
            final var phases=new forge.toolbox.special.PhaseIndicator();host.add(phases);
            final var label=phases.getLabelFor(forge.game.phase.PhaseType.MAIN1);
            Assert.assertEquals(((MatchSkinTheme.Style)label.getClientProperty(MatchSkinTheme.STYLE_PROPERTY)).fill(),Color.RED);
            final boolean before=label.getEnabled();
            label.dispatchEvent(new java.awt.event.MouseEvent(label,java.awt.event.MouseEvent.MOUSE_PRESSED,0,0,2,2,1,false,java.awt.event.MouseEvent.BUTTON1));
            Assert.assertEquals(label.getEnabled(),!before);
            host.remove(phases);Assert.assertNull(label.getClientProperty(MatchSkinTheme.STYLE_PROPERTY));
            restore.run();Assert.assertEquals(host.getContainerListeners().length,0);
        });
    }

    @Test public void disabledButtonKeepsItsOriginalActionRules() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            try (var skin=org.mockito.Mockito.mockStatic(forge.toolbox.FSkin.class,org.mockito.Mockito.CALLS_REAL_METHODS)) {
            // Sprite-backed legacy art is not loaded in this headless test; the v4 branch supplies it.
            skin.when(() -> forge.toolbox.FSkin.getIcon(org.mockito.Mockito.any(forge.localinstance.skin.FSkinProp.class))).thenReturn(null);
            final var button=new forge.toolbox.FButton("Confirm");
            final var clicks=new java.util.concurrent.atomic.AtomicInteger();
            button.addActionListener(e -> clicks.incrementAndGet());
            final var t=theme("{\"styles\":{\"button\":{\"fill\":\"#FFFFFF\",\"states\":{\"disabled\":{\"fill\":\"#0000FF\"},\"selected\":{\"fill\":\"#00FF00\"}}}}}",null);
            final var restore=t.apply(button,"PROMPT_OK");
            button.setSize(100,40); button.setEnabled(false);
            final var image=canvas(100,40); final var g=image.createGraphics();
            try { button.paint(g); } finally { g.dispose(); }
            Assert.assertEquals(image.getRGB(15,15),Color.BLUE.getRGB());
            button.doClick(0); Assert.assertEquals(clicks.get(),0);
            button.setEnabled(true);button.doClick(0);Assert.assertEquals(clicks.get(),1);
            button.setToggled(true);
            final var toggled=image.createGraphics();
            try { button.paint(toggled); } finally { toggled.dispose(); }
            Assert.assertEquals(image.getRGB(15,15),Color.GREEN.getRGB());
            restore.run();Assert.assertNull(button.getClientProperty(MatchSkinTheme.STYLE_PROPERTY));
            }
        });
    }

    @Test public void generatedV4PackageImportsAndOwnsAllDocuments() throws Exception {
        final String path=System.getProperty("forge.skinV4Example");
        if (path==null) { return; } // Optional generated-artifact integration check, enabled by the release verification command.
        final Path temporary=Files.createTempDirectory("forge-v4-import-");
        try {
            final String name=MatchSkinPackages.install(Path.of(path),temporary);
            final var root=temporary.resolve(name);
            try(var reader=Files.newBufferedReader(root.resolve("match-ui.json"))) {
                final var layout=MatchUiLayout.read(reader,root);
                final var docs=List.of("FIELD_0","FIELD_1","HAND_0","REPORT_STACK","REPORT_MESSAGE","REPORT_LOG","REPORT_COMBAT",
                        "REPORT_DEPENDENCIES","BUTTON_DOCK","CARD_PICTURE","CARD_DETAIL");
                Assert.assertTrue(layout.scene().supports(docs));
                Assert.assertFalse(layout.arrange(docs).stream().flatMap(c->c.documents().stream()).anyMatch("CARD_DETAIL"::equals));
                Assert.assertNotNull(layout.scene().appearance().style("FIELD_0.ZONE_LIBRARY").visual().icon());
                Assert.assertNotNull(layout.scene().appearance().style("PHASE.MAIN1").visual().icon());
                Assert.assertEquals(layout.scene().appearance().style("floating.CARD_DETAIL").fill().getAlpha(),153);
            }
        } finally {
            try(var files=Files.walk(temporary)) {for(var file:files.sorted(java.util.Comparator.reverseOrder()).toList()) {Files.delete(file);}}
        }
    }
}
