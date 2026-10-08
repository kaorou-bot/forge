package forge;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Align;
import forge.assets.FSkinFont;
import forge.util.Localizer;
import forge.util.TextBounds;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class GraphicsLanguageStabilityTest {
    private String chineseCancel;

    @BeforeClass
    public void initializeMobilePaths() throws Exception {
        MobileTestEnvironment.initialize();
        Localizer.getInstance().initialize("zh-CN", "../forge-gui/res/languages");
        chineseCancel = Localizer.getInstance().getMessage("lblCancel");
        Assert.assertNotEquals(chineseCancel, Localizer.getInstance().getEnglishMessage("lblCancel"));
    }

    @BeforeMethod
    public void restoreChinese() {
        Localizer.getInstance().setEnglish(false);
    }

    @Test
    public void layoutFailureKeepsChineseAndNextDrawSucceeds() {
        FSkinFont font = mock(FSkinFont.class);
        doThrow(new IllegalStateException("Transient glyph layout failure")).doAnswer(invocation -> {
            TextBounds bounds = invocation.getArgument(1);
            bounds.set(10, 10);
            return null;
        }).when(font).getMultiLineBounds(anyString(), any(TextBounds.class));

        Graphics graphics = new Graphics(mock(SpriteBatch.class), mock(ShapeRenderer.class));
        graphics.drawText("中文", font, Color.WHITE, 0, 0, 100, 100, false, Align.left, false);
        Assert.assertEquals(Localizer.getInstance().getMessage("lblCancel"), chineseCancel);
        graphics.drawText("中文", font, Color.WHITE, 0, 0, 100, 100, false, Align.left, false);
        verify(font).draw(any(), eq("中文"), any(), anyFloat(), anyFloat(), anyFloat(), eq(false), eq(Align.left));
        Assert.assertEquals(Localizer.getInstance().getMessage("lblCancel"), chineseCancel);
    }

    @Test
    public void drawFailureKeepsChineseAcrossRepeatedFrames() {
        FSkinFont font = mock(FSkinFont.class);
        doAnswer(invocation -> {
            TextBounds bounds = invocation.getArgument(1);
            bounds.set(10, 10);
            return null;
        }).when(font).getMultiLineBounds(anyString(), any(TextBounds.class));
        doThrow(new IllegalStateException("Transient texture failure")).doNothing()
                .when(font).draw(any(), anyString(), any(), anyFloat(), anyFloat(), anyFloat(), anyBoolean(), anyInt());

        Graphics graphics = new Graphics(mock(SpriteBatch.class), mock(ShapeRenderer.class));
        for (int frame = 0; frame < 100; frame++) {
            graphics.drawText("中文", font, Color.WHITE, 0, 0, 100, 100, false, Align.left, false);
            Assert.assertEquals(Localizer.getInstance().getMessage("lblCancel"), chineseCancel);
        }
        verify(font, times(100)).draw(any(), eq("中文"), any(), anyFloat(), anyFloat(), anyFloat(), eq(false), eq(Align.left));
    }

    @Test
    public void deliberatelySelectedEnglishStillWorks() {
        Localizer localizer = Localizer.getInstance();
        try {
            localizer.setLanguage("en-US", "../forge-gui/res/languages");
            Assert.assertEquals(localizer.getMessage("lblCancel"), "Cancel");
        } finally {
            localizer.setLanguage("zh-CN", "../forge-gui/res/languages");
        }
    }
}
