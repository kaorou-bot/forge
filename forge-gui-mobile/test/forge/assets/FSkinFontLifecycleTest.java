package forge.assets;

import java.lang.reflect.Method;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.PixmapPacker;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeBitmapFontData;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import forge.MobileTestEnvironment;
import forge.Forge;
import forge.gui.FThreads;
import forge.util.Localizer;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class FSkinFontLifecycleTest {
    @BeforeClass
    public void initializeMobilePaths() throws Exception {
        MobileTestEnvironment.initialize();
    }

    @Test
    public void replacingIncrementalFontReleasesTexturesAndNativeData() {
        FSkinFont skinFont = mock(FSkinFont.class, CALLS_REAL_METHODS);
        BitmapFont previous = mock(BitmapFont.class);
        FreeTypeBitmapFontData data = mock(FreeTypeBitmapFontData.class);
        when(previous.getData()).thenReturn(data);
        BitmapFont replacement = mock(BitmapFont.class);

        skinFont.replaceFont(previous, true);
        skinFont.replaceFont(replacement, true);
        verify(previous).dispose();
        verify(data).dispose();
        Assert.assertSame(skinFont.font, replacement);
        skinFont.dispose();
        skinFont.dispose();
        verify(replacement, times(1)).dispose();
        Assert.assertNull(skinFont.font);
    }

    @Test
    public void failedReplacementKeepsWorkingFont() {
        FSkinFont skinFont = mock(FSkinFont.class, CALLS_REAL_METHODS);
        BitmapFont previous = mock(BitmapFont.class);
        skinFont.replaceFont(previous, true);
        skinFont.replaceFont(null, true);
        Assert.assertSame(skinFont.font, previous);
        verify(previous, never()).dispose();
        skinFont.dispose();
    }

    @Test
    public void assetManagerFontIsNotDoubleDisposed() {
        FSkinFont skinFont = mock(FSkinFont.class, CALLS_REAL_METHODS);
        BitmapFont managedFont = mock(BitmapFont.class);
        skinFont.replaceFont(managedFont, false);
        skinFont.replaceFont(mock(BitmapFont.class), true);
        verify(managedFont, never()).dispose();
        skinFont.dispose();
    }

    @Test
    public void incrementalGenerationFailureKeepsChineseAndAllowsRetry() throws Exception {
        Localizer localizer = Localizer.getInstance();
        localizer.initialize("zh-CN", "../forge-gui/res/languages");
        localizer.setEnglish(false);
        String chineseCancel = localizer.getMessage("lblCancel");
        Forge.locale = "zh-CN";
        FSkinFont skinFont = mock(FSkinFont.class, CALLS_REAL_METHODS);
        doReturn("中文").when(skinFont).getCharacterSet(anyString());
        BitmapFont previous = mock(BitmapFont.class);
        BitmapFont replacement = mock(BitmapFont.class);
        skinFont.replaceFont(previous, true);
        Method generate = FSkinFont.class.getDeclaredMethod("generateIncrementalFont", FileHandle.class, int.class);
        generate.setAccessible(true);

        try (MockedStatic<FThreads> threads = mockStatic(FThreads.class);
             MockedConstruction<PixmapPacker> packers = mockConstruction(PixmapPacker.class);
             MockedConstruction<FreeTypeFontGenerator> generators = mockConstruction(FreeTypeFontGenerator.class,
                     (generator, context) -> {
                         when(generator.hasGlyph(anyInt())).thenReturn(true);
                         when(generator.generateFont(any(FreeTypeFontParameter.class)))
                                 .thenThrow(new IllegalStateException("Transient font generation failure"))
                                 .thenReturn(replacement);
                     })) {
            threads.when(() -> FThreads.invokeInEdtNowOrLater(any(Runnable.class))).thenAnswer(invocation -> {
                ((Runnable) invocation.getArgument(0)).run();
                return null;
            });
            FileHandle ttf = new FileHandle("target/test-cjk-font.ttf");
            generate.invoke(skinFont, ttf, 20);
            Assert.assertSame(skinFont.font, previous);
            Assert.assertEquals(localizer.getMessage("lblCancel"), chineseCancel);
            verify(previous, never()).dispose();
            verify(packers.constructed().get(0)).dispose();

            generate.invoke(skinFont, ttf, 20);
            Assert.assertSame(skinFont.font, replacement);
            Assert.assertEquals(localizer.getMessage("lblCancel"), chineseCancel);
            verify(previous).dispose();
            skinFont.dispose();
            FSkinFont.disposeIncrementalGenerators();
            verify(generators.constructed().get(0)).dispose();
        } finally {
            FSkinFont.disposeIncrementalGenerators();
        }
    }
}
