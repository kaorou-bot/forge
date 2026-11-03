package forge.adventure.util;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.Batch;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.scenes.scene2d.ui.Table;
import com.badlogic.gdx.scenes.scene2d.ui.TextButton;
import com.badlogic.gdx.utils.Array;
import com.github.tommyettinger.textra.Font;
import com.github.tommyettinger.textra.Layout;
import com.github.tommyettinger.textra.TextraLabel;
import com.github.tommyettinger.textra.TypingLabel;
import com.github.tommyettinger.textra.TypingConfig;
import com.github.tommyettinger.textra.TypingListener;
import forge.MobileTestEnvironment;
import org.mockito.MockedStatic;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

/** Real BMFont/Textra parsers, layout, scene2d tables and typing progression; GPU upload is mocked. */
public class AdventureTextWrappingTest {
    private static final String LONG = "为了寻找失落的宝藏你必须穿过古老森林到达遥远村庄与守护者交谈然后继续探索未知世界".repeat(3);

    private static Path root() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("forge-gui/res"))) path = path.getParent();
        if (path == null) throw new IllegalStateException("Cannot find resource root");
        return path;
    }

    private static final class Fixture implements AutoCloseable {
        final com.badlogic.gdx.Graphics oldGraphics = Gdx.graphics;
        final com.badlogic.gdx.Application oldApp = Gdx.app;
        final com.badlogic.gdx.Files oldFiles = Gdx.files;
        final Skin skin = new Skin();
        final Font font;
        final MockedStatic<Controls> controls;

        Fixture() throws Exception {
            MobileTestEnvironment.initialize();
            Gdx.graphics = mock(com.badlogic.gdx.Graphics.class);
            Gdx.app = mock(com.badlogic.gdx.Application.class);
            Gdx.files = mock(com.badlogic.gdx.Files.class);
            when(Gdx.graphics.getWidth()).thenReturn(1080);
            when(Gdx.graphics.getHeight()).thenReturn(1920);
            when(Gdx.graphics.getBackBufferWidth()).thenReturn(1080);
            when(Gdx.graphics.getBackBufferHeight()).thenReturn(1920);
            final BitmapFont.BitmapFontData data = new BitmapFont.BitmapFontData(new FileHandle(root()
                    .resolve("forge-gui/res/adventure/common/skin/ForgeAdventurePixel.fnt").toFile()), false);
            final Array<TextureRegion> regions = new Array<>();
            for (String ignored : data.imagePaths) {
                final Texture texture = mock(Texture.class);
                when(texture.getWidth()).thenReturn(2048);
                when(texture.getHeight()).thenReturn(2048);
                regions.add(new TextureRegion(texture));
            }
            final BitmapFont bitmap = new BitmapFont(data, regions, false);
            font = new Font(bitmap, 0f, 2f, 0f, 1f);
            skin.add("default", bitmap);
            skin.add("default", new Label.LabelStyle(bitmap, Color.WHITE));
            final TextButton.TextButtonStyle button = new TextButton.TextButtonStyle();
            button.font = bitmap;
            button.fontColor = Color.WHITE;
            skin.add("default", button);
            controls = mockStatic(Controls.class);
            controls.when(Controls::getSkin).thenReturn(skin);
            controls.when(Controls::getTextraFont).thenAnswer(call -> AdventureText.prepareFont(font));
        }

        @Override public void close() {
            controls.close();
            font.dispose();
            skin.dispose();
            Gdx.graphics = oldGraphics;
            Gdx.app = oldApp;
            Gdx.files = oldFiles;
        }
    }

    private static Layout displayed(TextraLabel label) {
        return label instanceof TypingLabel ? ((TypingLabel) label).getWorkingLayout() : label.layout;
    }

    private static String visible(String text) {
        return text.replace("\u200B", "").replace("\n", "").replace("\r", "");
    }

    private static void fit(TextraLabel label, float width) {
        label.setWrap(true);
        label.setWidth(width);
        label.layout();
        if (label instanceof TypingLabel) {
            ((TypingLabel) label).skipToTheEnd();
            label.act(0.001f);
            label.layout();
        }
    }

    private static void assertFits(TextraLabel label, float width, String text) {
        final Layout layout = displayed(label);
        assertTrue(layout.lines() > 1, "Expected actual wrapping");
        final Batch batch = mock(Batch.class);
        when(batch.getColor()).thenReturn(Color.WHITE);
        when(batch.getProjectionMatrix()).thenReturn(new Matrix4());
        final float[] right = {0};
        doAnswer(call -> {
            final float[] vertices = call.getArgument(1);
            final int color = Float.floatToRawIntBits(vertices[2]);
            if ((color >>> 25) != 0 && vertices[0] != vertices[10] && vertices[1] != vertices[6]) {
                for (int v = 0; v < 20; v += 5) right[0] = Math.max(right[0], vertices[v]);
            }
            return null;
        }).when(batch).draw(any(Texture.class), any(float[].class), eq(0), eq(20));
        final StringBuilder actual = new StringBuilder();
        for (int i = 0; i < layout.lines(); i++) {
            float x = 0;
            int kern = -1;
            right[0] = 0;
            for (int j = 0; j < layout.getLine(i).glyphs.size; j++) {
                final long glyph = layout.getLine(i).glyphs.get(j);
                final char c = (char) glyph;
                if (label.getFont().kerning != null) {
                    kern = kern << 16 | c;
                    x += label.getFont().kerning.get(kern, 0) * label.getFont().scaleX;
                }
                x += label.getFont().drawGlyph(batch, glyph, x, 0);
                if ((glyph >>> 33 & 127) != 0 && c != '\u200B') actual.append(c == 2 ? '[' : c);
            }
            assertTrue(right[0] <= width + 0.01f,
                    label.getClass().getSimpleName() + " line " + i + " visible edge=" + right[0]
                            + " > " + width + "; measured width=" + layout.getLine(i).width);
        }
        assertEquals(visible(actual.toString()), visible(text), "Visible glyph order/content");
    }

    @DataProvider public Object[][] widths() {
        return new Object[][] {{120f}, {220f}, {300f}};
    }

    @Test(dataProvider = "widths")
    public void longChineseWrapsWhileUnmodifiedLibraryOverflows(float width) throws Exception {
        try (Fixture f = new Fixture()) {
            final TextraLabel original = new TextraLabel(LONG, f.font);
            fit(original, width);
            assertTrue(original.layout.getWidth() > width, "Baseline must reproduce unbreakable Chinese overflow");
            final TextraLabel fixed = new Controls.LabelFix(LONG, f.font);
            fit(fixed, width);
            System.out.println("WRAP width=" + width + " baseline=" + original.layout.getWidth()
                    + " fixedFirst=" + fixed.layout.getLine(0) + " zwspAdvance=" + f.font.mapping.get('\u200B').xAdvance);
            assertFits(fixed, width, LONG);
            final TypingLabel typing = new AdventureTypingLabel(LONG, f.font, Color.WHITE);
            fit(typing, width);
            assertFits(typing, width, LONG);
        }
    }

    @Test public void resizeAndSetTextReflowWithoutLosingCharacters() throws Exception {
        try (Fixture f = new Fixture()) {
            for (TextraLabel label : new TextraLabel[] {new Controls.LabelFix(LONG, f.font),
                    new AdventureTypingLabel(LONG, f.font, Color.WHITE)}) {
                fit(label, 120);
                final int narrowLines = displayed(label).lines();
                fit(label, 300);
                assertFits(label, 300, LONG);
                assertTrue(displayed(label).lines() < narrowLines);
                label.setText("新的任务描述" + LONG);
                fit(label, 220);
                assertFits(label, 220, "新的任务描述" + LONG);
                fit(label, 120);
                assertFits(label, 120, "新的任务描述" + LONG);
            }
        }
    }

    @DataProvider public Object[][] plainCases() {
        return new Object[][] {{null, ""}, {"", ""}, {"OK", "OK"}, {"Level 12 + 3 = 15", "Level 12 + 3 = 15"},
                {"你好，世界！任务（等级12）完成。", "你好，世界！任务（等级12）完成。"},
                {"第一行\n第二行", "第一行\n第二行"}, {"中\u200B文", "中文"}, {"[[中文", "[中文"}};
    }

    @Test(dataProvider = "plainCases")
    public void ordinaryTextAndEscapedBracketsKeepVisibleContent(String input, String expected) throws Exception {
        assertEquals(AdventureText.wrap(AdventureText.wrap(input)), AdventureText.wrap(input), "Idempotence");
        try (Fixture f = new Fixture()) {
            final TextraLabel label = new Controls.LabelFix(input, f.font);
            final TypingLabel typing = new AdventureTypingLabel(input, f.font, Color.WHITE);
            fit(label, 220);
            fit(typing, 220);
            assertEquals(visible(label.layout.toString()), visible(expected));
            assertEquals(visible(typing.getWorkingLayout().toString()), visible(expected));
            if (input != null && input.contains("\n")) {
                assertEquals(label.layout.lines(), 2);
                assertEquals(typing.getWorkingLayout().lines(), 2);
            }
            if (input != null && input.chars().allMatch(cp -> cp < 128)) {
                final TextraLabel baseline = new TextraLabel(input, f.font);
                assertEquals(label.layout.getWidth(), baseline.layout.getWidth(), 0.001f);
            }
        }
    }

    @Test public void publicNullSetterProducesEmptyText() throws Exception {
        try (Fixture f = new Fixture()) {
            for (TextraLabel label : new TextraLabel[] {new Controls.LabelFix("旧文字", f.font),
                    new AdventureTypingLabel("旧文字", f.font, Color.WHITE)}) {
                label.setText(null);
                fit(label, 220);
                assertEquals(visible(displayed(label).toString()), "");
            }
        }
    }

    @Test public void npcDialogueWithAvatarFitsItsActualTextCell() throws Exception {
        try (Fixture f = new Fixture()) {
            final String text = "这些是挑战币。山德拉的施法者常常聚在一起切磋技艺。用这些钱币就能报名，赢得的奖品会为你的旅程提供帮助。";
            final Table table = new Table();
            final TypingLabel label = new AdventureTypingLabel(text, f.font, Color.WHITE);
            label.setWrap(true);
            table.add(new Table()).width(30);
            table.add(label).width(250 - 30).fillX();
            table.pack(); table.validate();
            fit(label, label.getWidth());
            assertEquals(label.getWidth(), 220f, 0.001f);
            assertFits(label, 220, text);
        }
    }

    @Test public void typingButtonGetterTracksUpdatedText() throws Exception {
        try (Fixture f = new Fixture()) {
            final Controls.TypingButtonFix button = new Controls.TypingButtonFix("旧文字");
            button.setText("OK");
            assertEquals(button.getText(), "OK");
        }
    }

    @Test public void punctuationAndTokenSpellingArePreserved() {
        final String tokens = "甲{VAR=player_name}{IF=choice;yes=中文;否则}{EVENT=选择;甲乙}{WAIT=0.25}"
                + "[RED]乙[+图标]{GRADIENT=CYAN;BLUE;1;1}丙{ENDGRADIENT}丁";
        assertEquals(AdventureText.wrap(tokens).replace("\u200B", ""), tokens);
        final String text = AdventureText.wrap("甲（乙）丙，丁。戊！己？庚");
        assertFalse(text.contains("（\u200B"));
        for (String mark : new String[] {"）", "，", "。", "！", "？"}) assertFalse(text.contains("\u200B" + mark));
        assertEquals(AdventureText.wrap("12345 ABC"), "12345 ABC");
    }

    private static final class Trace implements TypingListener {
        final StringBuilder chars = new StringBuilder();
        final List<String> events = new ArrayList<>();
        final List<Float> times = new ArrayList<>();
        float now;
        int ends;
        @Override public void event(String event) { events.add(event + "@" + visible(chars.toString())); }
        @Override public void end() { ends++; }
        @Override public String replaceVariable(String variable) { return null; }
        @Override public void onChar(long ch) {
            char c = (char) ch;
            if (c == 2) c = '[';
            chars.append(c);
            if (c != '\u200B') times.add(now);
        }
    }

    private static Trace run(TypingLabel label, boolean skip) {
        final Trace trace = new Trace();
        label.setTypingListener(trace);
        label.parseTokens();
        if (skip) label.skipToTheEnd(false);
        for (int frame = 0; frame < 50000 && !label.hasEnded(); frame++) {
            trace.now += 0.001f;
            label.act(0.001f);
        }
        assertTrue(label.hasEnded(), "Typing failed to finish");
        assertEquals(trace.ends, 1);
        return trace;
    }

    @DataProvider public Object[][] progression() { return new Object[][] {{false}, {true}}; }

    @Test(dataProvider = "progression")
    public void variablesConditionsEventsAndWaitKeepExactVisiblePositions(boolean skip) throws Exception {
        try (Fixture f = new Fixture()) {
            final String source = "甲{EVENT=开始;甲乙}{VAR=hero}{EVENT=变量之后}{WAIT=0.12}"
                    + "{IF=choice;yes=长长的中文分支;默认}{EVENT=分支之后}{GRADIENT=CYAN;BLUE;1;1}终点{ENDGRADIENT}{EVENT=结束}";
            final TypingLabel label = new AdventureTypingLabel(source, f.font, Color.WHITE);
            label.setVariable("hero", "勇敢的中文名字");
            label.setVariable("choice", "yes");
            final Trace trace = run(label, skip);
            assertEquals(visible(trace.chars.toString()), "甲勇敢的中文名字长长的中文分支终点");
            assertEquals(trace.events, List.of("开始;甲乙@甲", "变量之后@甲勇敢的中文名字",
                    "分支之后@甲勇敢的中文名字长长的中文分支", "结束@甲勇敢的中文名字长长的中文分支终点"));
        }
    }

    @Test public void invisibleBreakDoesNotAddTypingDelay() throws Exception {
        try (Fixture f = new Fixture()) {
            final String input = "甲乙丙丁戊己庚辛壬癸";
            final Trace original = run(new TypingLabel(input, f.font, Color.WHITE), false);
            final Trace fixed = run(new AdventureTypingLabel(input, f.font, Color.WHITE), false);
            assertEquals(visible(fixed.chars.toString()), input);
            assertEquals(fixed.times.size(), original.times.size());
            for (int i = 0; i < original.times.size(); i++) {
                assertEquals(fixed.times.get(i), original.times.get(i), 0.002f, "Visible char " + i + " delayed by break");
            }
        }
    }

    @Test public void waitStillDelaysAtTheCorrectVisibleBoundary() throws Exception {
        try (Fixture f = new Fixture()) {
            final Trace original = run(new TypingLabel("甲乙{WAIT=0.2}丙丁", f.font, Color.WHITE), false);
            final Trace fixed = run(new AdventureTypingLabel("甲乙{WAIT=0.2}丙丁", f.font, Color.WHITE), false);
            assertEquals(visible(fixed.chars.toString()), "甲乙丙丁");
            assertTrue(fixed.times.get(2) - fixed.times.get(1) >= 0.19f, "WAIT must pause before 丙");
            for (int i = 0; i < original.times.size(); i++) assertEquals(fixed.times.get(i), original.times.get(i), 0.002f);
        }
    }

    @Test public void colorsAndInlineImagesSurviveRealMarkup() throws Exception {
        try (Fixture f = new Fixture()) {
            f.font.addImage("图标", f.font.mapping.get('甲'), 0, 0, 12);
            final String text = "[RED]甲乙[+图标][BLUE]丙丁";
            final TextraLabel original = new TextraLabel(text, f.font);
            final TextraLabel fixed = new Controls.LabelFix(text, f.font);
            final List<Long> a = glyphs(original.layout), b = glyphs(fixed.layout);
            assertEquals(b, a, "Color bits, image codepoint and visible order must match the original parser");
        }
    }

    private static List<Long> glyphs(Layout layout) {
        final List<Long> result = new ArrayList<>();
        for (int i = 0; i < layout.lines(); i++) {
            for (int j = 0; j < layout.getLine(i).glyphs.size; j++) {
                long glyph = layout.getLine(i).glyphs.get(j);
                if ((char) glyph != '\u200B') result.add(glyph);
            }
        }
        return result;
    }

    @Test public void missingZeroWidthGlyphIsMadeInvisibleWithNoAdvance() throws Exception {
        try (Fixture f = new Fixture()) {
            f.font.mapping.remove('\u200B');
            AdventureText.prepareFont(f.font);
            final Font.GlyphRegion glyph = f.font.mapping.get('\u200B');
            assertNotNull(glyph);
            assertEquals(glyph.xAdvance, 0f);
            assertEquals(glyph.getRegionWidth(), 0);
            assertEquals(glyph.getRegionHeight(), 0);
            final Layout plain = new TextraLabel("甲乙", f.font).layout;
            final Layout broken = new TextraLabel("甲\u200B乙", f.font).layout;
            assertEquals(broken.getWidth(), plain.getWidth(), 0.001f);
            assertNotSame(glyph, f.font.defaultValue);
        }
    }

    @Test public void realButtonsConstructAndUpdateThroughBothPaths() throws Exception {
        try (Fixture f = new Fixture()) {
            final Controls.TextButtonFix plain = new Controls.TextButtonFix(LONG);
            final Controls.TypingButtonFix typing = new Controls.TypingButtonFix(LONG);
            assertTrue(typing.getTextraLabel() instanceof AdventureTypingLabel);
            fit(plain.getTextraLabel(), 120);
            fit(typing.getTextraLabel(), 120);
            assertFits(plain.getTextraLabel(), 120, LONG);
            assertFits(typing.getTextraLabel(), 120, LONG);
            assertEquals(plain.getText(), LONG);
            assertEquals(typing.getText(), LONG);
            plain.setText("新的" + LONG);
            typing.setText("新的" + LONG);
            fit(plain.getTextraLabel(), 220);
            fit(typing.getTextraLabel(), 220);
            assertFits(plain.getTextraLabel(), 220, "新的" + LONG);
            assertFits(typing.getTextraLabel(), 220, "新的" + LONG);
            plain.setText("OK"); typing.setText("OK");
            assertEquals(plain.getText(), "OK"); assertEquals(typing.getText(), "OK");
            plain.setText(null); typing.setText(null);
            assertEquals(plain.getText(), ""); assertEquals(typing.getText(), "");
        }
    }

    @Test(dataProvider = "widths")
    public void finiteTableCellActuallyConstrainsTheLabel(float width) throws Exception {
        try (Fixture f = new Fixture()) {
            final Table table = new Table();
            final TypingLabel label = new AdventureTypingLabel(LONG, f.font, Color.WHITE);
            label.setWrap(true);
            table.add(label).width(width).fillX();
            table.setWidth(width);
            table.pack();
            table.validate();
            fit(label, label.getWidth());
            assertEquals(label.getWidth(), width, 0.001f);
            assertFits(label, width, LONG);
        }
    }

    @DataProvider public Object[][] multiplierCases() {
        return new Object[][] {{false, false}, {true, false}, {false, true}, {true, true}};
    }

    @Test(dataProvider = "multiplierCases")
    public void globalBreakIntervalIsRestoredEvenWhenListenerThrows(boolean existing, boolean throwing) throws Exception {
        final boolean previousExists = TypingConfig.INTERVAL_MULTIPLIERS_BY_CHAR.containsKey('\u200B');
        final float previous = TypingConfig.INTERVAL_MULTIPLIERS_BY_CHAR.get('\u200B', 1f);
        try (Fixture f = new Fixture()) {
            if (existing) TypingConfig.INTERVAL_MULTIPLIERS_BY_CHAR.put('\u200B', 2.75f);
            else TypingConfig.INTERVAL_MULTIPLIERS_BY_CHAR.remove('\u200B', 1f);
            final TypingLabel label = new AdventureTypingLabel("甲乙丙", f.font, Color.WHITE);
            label.setTypingListener(new TypingListener() {
                @Override public void event(String event) { }
                @Override public void end() { }
                @Override public String replaceVariable(String variable) { return null; }
                @Override public void onChar(long ch) {
                    assertEquals(TypingConfig.INTERVAL_MULTIPLIERS_BY_CHAR.get('\u200B', -1f), 0f);
                    if (throwing) throw new IllegalStateException("intentional test listener failure");
                }
            });
            label.parseTokens();
            label.skipToTheEnd(false);
            if (throwing) {
                final IllegalStateException failure = expectThrows(IllegalStateException.class, () -> label.act(0.001f));
                assertEquals(failure.getMessage(), "intentional test listener failure");
            } else {
                label.act(0.001f);
                assertTrue(label.hasEnded());
            }
            assertEquals(TypingConfig.INTERVAL_MULTIPLIERS_BY_CHAR.containsKey('\u200B'), existing);
            if (existing) assertEquals(TypingConfig.INTERVAL_MULTIPLIERS_BY_CHAR.get('\u200B', -1f), 2.75f);
        } finally {
            if (previousExists) TypingConfig.INTERVAL_MULTIPLIERS_BY_CHAR.put('\u200B', previous);
            else TypingConfig.INTERVAL_MULTIPLIERS_BY_CHAR.remove('\u200B', 1f);
        }
    }

    private static String lineVisible(com.github.tommyettinger.textra.Line line) {
        final StringBuilder result = new StringBuilder();
        for (int i = 0; i < line.glyphs.size; i++) {
            final long glyph = line.glyphs.get(i);
            if ((glyph >>> 33 & 127) != 0) result.append((char) glyph == 2 ? '[' : (char) glyph);
        }
        return visible(result.toString());
    }

    @Test public void repeatedWideNarrowResizePreservesExplicitNewlineAndAllText() throws Exception {
        try (Fixture f = new Fixture()) {
            final String text = "甲乙丙丁\n" + LONG;
            final TypingLabel label = new AdventureTypingLabel(text, f.font, Color.WHITE);
            label.setWrap(true);
            for (float width : new float[] {120, 300, 220, 120, 300, 120}) {
                label.setSize(width, 80);
                fit(label, width);
                assertFits(label, width, text);
                assertEquals(lineVisible(displayed(label).getLine(0)), "甲乙丙丁", "Explicit newline must remain");
            }
        }
    }

    // TypingLabel.draw uses this private index as the inclusive visible glyph boundary.
    private static String renderPrefix(TypingLabel label) {
        try {
            final java.lang.reflect.Field field = TypingLabel.class.getDeclaredField("glyphCharIndex");
            field.setAccessible(true);
            final int last = field.getInt(label);
            final StringBuilder result = new StringBuilder();
            int index = 0;
            for (int i = 0; i < label.getWorkingLayout().lines(); i++) {
                final com.github.tommyettinger.textra.Line line = label.getWorkingLayout().getLine(i);
                for (int j = 0; j < line.glyphs.size && index <= last; j++, index++) {
                    final long glyph = line.glyphs.get(j);
                    if ((glyph >>> 33 & 127) != 0) result.append((char) glyph == 2 ? '[' : (char) glyph);
                }
            }
            return visible(result.toString());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test(dataProvider = "progression")
    public void resizingDuringTypingPreservesEventsAndRenderedPrefix(boolean skipAfterResize) throws Exception {
        try (Fixture f = new Fixture()) {
            final String first = "这些是挑战币山德拉的施法者常常聚在一起切磋技艺";
            final String variable = "赢得的奖品会为你的旅程提供帮助";
            final String expected = first + variable + "结束";
            final TypingLabel label = new AdventureTypingLabel(first + "{EVENT=首段}{VAR=prize}"
                    + "{EVENT=奖品}{WAIT=0.1}结束{EVENT=终点}", f.font, Color.WHITE);
            label.setVariable("prize", variable);
            label.setWrap(true);
            label.setWidth(120);
            final Trace trace = new Trace();
            final List<String> renderedEvents = new ArrayList<>();
            label.setTypingListener(new TypingListener() {
                @Override public void event(String event) {
                    trace.event(event);
                    renderedEvents.add(event + "@" + renderPrefix(label));
                }
                @Override public void end() { trace.end(); }
                @Override public String replaceVariable(String variableName) { return null; }
                @Override public void onChar(long ch) { trace.onChar(ch); }
            });
            label.parseTokens();
            label.layout();
            for (int frame = 0; frame < 10000 && !label.hasEnded(); frame++) {
                if (frame == 100 || frame == 200 || frame == 300) {
                    final float width = frame == 100 ? 300 : frame == 200 ? 220 : 120;
                    label.setSize(width, 80);
                    label.layout();
                    assertFits(label, width, expected);
                    assertEquals(renderPrefix(label), visible(trace.chars.toString()), "Reflow changed shown prefix");
                    if (frame == 300 && skipAfterResize) label.skipToTheEnd(false);
                }
                trace.now += 0.001f;
                label.act(0.001f);
            }
            assertTrue(label.hasEnded());
            assertEquals(trace.ends, 1);
            assertEquals(visible(trace.chars.toString()), expected);
            final List<String> events = List.of("首段@" + first, "奖品@" + first + variable, "终点@" + expected);
            assertEquals(trace.events, events);
            assertEquals(renderedEvents, events, "Events must match the actual rendered prefix");
            assertFits(label, 120, expected);
        }
    }
}
