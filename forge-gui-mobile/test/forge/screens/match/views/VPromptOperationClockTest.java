package forge.screens.match.views;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import com.badlogic.gdx.Gdx;
import forge.MobileTestEnvironment;
import forge.assets.FSkin;
import forge.assets.FSkinFont;
import forge.assets.FSkinImageInterface;
import forge.assets.TextRenderer;
import forge.localinstance.skin.FSkinProp;
import forge.menu.FMagnifyView;
import forge.toolbox.FButton;
import forge.toolbox.FDisplayObject;
import forge.toolbox.FOverlay;
import forge.util.TextBounds;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real prompt, magnify view, layout and container lifecycle; only graphics are substituted. */
public class VPromptOperationClockTest {
    private MockedStatic<FSkinFont> fonts;
    private MockedStatic<FSkin> skins;
    private MockedStatic<FOverlay> overlays;
    private MockedConstruction<TextRenderer> renderers;
    private MockedConstruction<FButton> buttons;
    private com.badlogic.gdx.Graphics originalGraphics;
    private com.badlogic.gdx.Application originalApp;
    private FOverlay container;
    private VPrompt prompt;
    private FDisplayObject clock;
    private FSkinFont font;

    private static FOverlay container() {
        FOverlay result = new FOverlay(null) {
            @Override protected void doLayout(float width, float height) { }
        };
        result.setSize(800, 600);
        return result;
    }

    @BeforeMethod
    public void prepare() throws Exception {
        MobileTestEnvironment.initialize();
        forge.util.Localizer.getInstance().initialize("zh-CN", "../forge-gui/res/languages");
        originalGraphics = Gdx.graphics;
        originalApp = Gdx.app;
        Gdx.app = mock(com.badlogic.gdx.Application.class);
        Gdx.graphics = mock(com.badlogic.gdx.Graphics.class);
        when(Gdx.graphics.getWidth()).thenReturn(800);
        when(Gdx.graphics.getHeight()).thenReturn(600);
        font = mock(FSkinFont.class);
        when(font.getLineHeight()).thenReturn(18f);
        fonts = mockStatic(FSkinFont.class);
        fonts.when(() -> FSkinFont.get(anyInt())).thenReturn(font);
        fonts.when(() -> FSkinFont._get(anyInt())).thenReturn(font);
        // CancelButton is an FButton subclass, so mockConstruction(FButton.class)
        // does not intercept its constructor. Keep FSkinImage setup off native Pixmap.
        final Map<FSkinProp, FSkinImageInterface> images = new HashMap<>();
        skins = mockStatic(FSkin.class);
        skins.when(FSkin::getImages).thenReturn(images);
        renderers = mockConstruction(TextRenderer.class, (renderer, context) ->
            when(renderer.getWrappedBounds(anyString(), any(), anyFloat())).thenAnswer(call ->
                new TextBounds(200, ((String) call.getArgument(0)).split("\\n").length * 18f)));
        buttons = mockConstruction(FButton.class);
        container = container();
        overlays = mockStatic(FOverlay.class);
        overlays.when(FOverlay::getTopOverlay).thenReturn(container);
        prompt = new VPrompt("Keep", "Mulligan", null, null);
        prompt.setBounds(0, 500, 800, 90);
        clock = prompt.getChildAt(0);
        clock.screenPos.set(0, 500, 800, 24);
    }

    @AfterMethod(alwaysRun = true)
    public void release() {
        if (overlays != null) { overlays.close(); overlays = null; }
        if (buttons != null) { buttons.close(); buttons = null; }
        if (skins != null) { skins.close(); skins = null; }
        if (renderers != null) { renderers.close(); renderers = null; }
        if (fonts != null) { fonts.close(); fonts = null; }
        Gdx.graphics = originalGraphics;
        Gdx.app = originalApp;
    }

    private FMagnifyView details() {
        for (FDisplayObject child : container.getChildren()) {
            if (child instanceof FMagnifyView) return (FMagnifyView) child;
        }
        throw new AssertionError("Expected visible clock details");
    }

    private static String text(FMagnifyView view) throws Exception {
        Field field = FMagnifyView.class.getDeclaredField("text");
        field.setAccessible(true);
        return (String) field.get(view);
    }

    @Test
    public void liveUpdateChangesRealTextAndRelayoutsForMorePlayers() throws Exception {
        prompt.setOperationClock("A 15:00");
        clock.tap(0, 0, 1);
        FMagnifyView view = details();
        float height = view.getHeight();
        prompt.setOperationClock("A 14:59  |  B 15:00");
        Assert.assertSame(details(), view);
        Assert.assertTrue(text(view).endsWith("A 14:59\nB 15:00"));
        Assert.assertTrue(view.getHeight() > height, "Real updateText must recalculate layout");
    }

    @Test
    public void dismissedDetailsStayClosedAndReopenAtLatestValue() throws Exception {
        prompt.setOperationClock("A 15:00");
        clock.tap(0, 0, 1);
        FMagnifyView old = details();
        old.tap(0, 0, 1);
        prompt.setOperationClock("A 14:20");
        Assert.assertEquals(container.getChildCount(), 0);
        Assert.assertFalse(old.isVisible());
        old.updateText("must not reopen");
        Assert.assertTrue(text(old).endsWith("A 15:00"));
        clock.tap(0, 0, 1);
        Assert.assertTrue(text(details()).endsWith("A 14:20"));
        Assert.assertNotSame(details(), old);
    }

    @Test
    public void clearingClockRemovesDetailsAndPreventsEmptyPopup() {
        prompt.setOperationClock("A 15:00");
        clock.tap(0, 0, 1);
        FMagnifyView old = details();
        prompt.setOperationClock("");
        Assert.assertFalse(old.isVisible());
        Assert.assertFalse(clock.isVisible());
        Assert.assertEquals(container.getChildCount(), 0);
        clock.tap(0, 0, 1);
        Assert.assertEquals(container.getChildCount(), 0);
    }

    @Test
    public void repeatedOpeningLeavesOnlyOnePopupAndBackdrop() {
        prompt.setOperationClock("A 15:00");
        clock.tap(0, 0, 1);
        FMagnifyView old = details();
        clock.tap(0, 0, 1);
        Assert.assertFalse(old.isVisible());
        Assert.assertNotSame(details(), old);
        Assert.assertEquals(container.getChildCount(), 2);
    }

    @Test
    public void hiddenViewIsRemovedFromOriginalContainerAfterScreenChange() {
        prompt.setOperationClock("A 15:00");
        clock.tap(0, 0, 1);
        FMagnifyView view = details();
        FOverlay next = container();
        overlays.when(FOverlay::getTopOverlay).thenReturn(next);
        prompt.setOperationClock("");
        Assert.assertFalse(view.isVisible());
        Assert.assertEquals(container.getChildCount(), 0, "Original popup and backdrop removed");
        Assert.assertEquals(next.getChildCount(), 0);
    }

    @Test
    public void legacyStaticShowStillDisplaysAndDismisses() throws Exception {
        FMagnifyView.show(clock, "ordinary help", null, null, font, false);
        FMagnifyView view = details();
        Assert.assertEquals(text(view), "ordinary help");
        Assert.assertTrue(view.isVisible());
        view.tap(0, 0, 1);
        Assert.assertFalse(view.isVisible());
        Assert.assertEquals(container.getChildCount(), 0);
    }
}
