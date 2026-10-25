package forge.adventure.util;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.scenes.scene2d.ui.*;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.github.tommyettinger.textra.Font;
import org.mockito.MockedConstruction;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

/** Real GDX/Skin/Textra parsers with only GPU texture upload replaced by mocks. */
public class AdventurePixelFontTest {
    private static Path root() {
        Path path = Path.of("").toAbsolutePath();
        while (path != null && !Files.isDirectory(path.resolve("forge-gui/res"))) path = path.getParent();
        if (path == null) throw new IllegalStateException("Cannot find resources");
        return path;
    }

    private static Path skinDirectory() {
        return root().resolve("forge-gui/res/adventure/common/skin");
    }

    private static Skin skin(String language, TextureAtlas atlas) throws Exception {
        final Method method = AdventureSkinLoader.class.getDeclaredMethod("newSkin", TextureAtlas.class);
        method.setAccessible(true);
        return (Skin) method.invoke(new AdventureSkinLoader(language), atlas);
    }

    private static MockedConstruction<Texture> textures(List<Texture> loaded) {
        return textures(loaded, new ArrayList<>());
    }

    private static MockedConstruction<Texture> textures(List<Texture> loaded, List<String> attempted) {
        return mockConstruction(Texture.class, (texture, context) -> {
            final FileHandle file = (FileHandle) context.arguments().get(0);
            attempted.add(file.name());
            if (!file.exists()) throw new GdxRuntimeException("Missing texture: " + file);
            final BufferedImage image = ImageIO.read(file.file());
            if (image == null) throw new GdxRuntimeException("Invalid texture: " + file);
            when(texture.getWidth()).thenReturn(image.getWidth());
            when(texture.getHeight()).thenReturn(image.getHeight());
            loaded.add(texture);
        });
    }

    private static com.badlogic.gdx.Files mockFiles() {
        final com.badlogic.gdx.Files files = mock(com.badlogic.gdx.Files.class);
        when(files.getFileHandle(anyString(), any())).thenAnswer(call -> new FileHandle((String) call.getArgument(0)));
        when(files.internal(anyString())).thenAnswer(call -> new FileHandle((String) call.getArgument(0)));
        return files;
    }

    @Test
    public void gdxParsesAllGlyphsAndBothPages() {
        final BitmapFont.BitmapFontData data = new BitmapFont.BitmapFontData(
                new FileHandle(skinDirectory().resolve(AdventureSkinLoader.PIXEL_FONT).toFile()), false);
        assertEquals(data.lineHeight, 16f);
        assertEquals(data.imagePaths.length, 2);
        assertNotNull(data.missingGlyph);
        int count = 0;
        final int[] pages = new int[2];
        for (int cp = 1; cp <= 65535; cp++) {
            final BitmapFont.Glyph glyph = data.getGlyph((char) cp);
            if (glyph == null) continue;
            count++;
            assertTrue(glyph.page >= 0 && glyph.page < 2);
            pages[glyph.page]++;
            assertTrue(glyph.srcX >= 0 && glyph.srcY >= 0);
            assertTrue(glyph.srcX + glyph.width <= 2048 && glyph.srcY + glyph.height <= 2048);
        }
        assertEquals(count + 1, 36471);
        assertTrue(pages[0] > 0 && pages[1] > 0);
    }

    @Test
    public void actualSkinStylesAndTextraUseBothPagesAndDisposeTextures() throws Exception {
        final com.badlogic.gdx.Files previous = Gdx.files;
        Gdx.files = mockFiles();
        final List<Texture> loaded = new ArrayList<>();
        try (var ignored = textures(loaded)) {
            final TextureAtlas atlas = new TextureAtlas(new FileHandle(skinDirectory().resolve("ui_skin.atlas").toFile()));
            final Skin skin = skin("zh-CN", atlas);
            try {
                skin.load(new FileHandle(skinDirectory().resolve("ui_skin.json").toFile()));
                final BitmapFont bitmap = skin.getFont("default");
                assertEquals(bitmap.getData().lineHeight, 16f);
                assertEquals(bitmap.getRegions().size, 2);
                for (int page = 0; page < 2; page++) {
                    verify(bitmap.getRegion(page).getTexture(), times(1))
                            .setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
                }
                assertSame(skin.get(Label.LabelStyle.class).font, bitmap);
                assertSame(skin.get(TextButton.TextButtonStyle.class).font, bitmap);
                assertSame(skin.get(TextField.TextFieldStyle.class).font, bitmap);
                assertSame(skin.get(com.badlogic.gdx.scenes.scene2d.ui.List.ListStyle.class).font, bitmap);
                final Font textra = new Font(bitmap, 0f, 2f, 0f, 1f);
                for (int page = 0; page < 2; page++) {
                    boolean checked = false;
                    for (int cp = 33; cp <= 65535; cp++) {
                        final BitmapFont.Glyph glyph = bitmap.getData().getGlyph((char) cp);
                        if (glyph != null && glyph.page == page && glyph.width > 0) {
                            assertNotNull(textra.mapping.get(cp));
                            assertSame(textra.mapping.get(cp).getTexture(), bitmap.getRegion(page).getTexture());
                            checked = true;
                            break;
                        }
                    }
                    assertTrue(checked);
                }
                textra.dispose();
                for (int page = 0; page < 2; page++) {
                    verify(bitmap.getRegion(page).getTexture(), never()).dispose();
                }
            } finally {
                skin.dispose();
            }
            for (Texture texture : loaded) verify(texture, times(1)).dispose();
        } finally {
            Gdx.files = previous;
        }
    }

    @DataProvider(name = "fallbackCases")
    public Object[][] fallbackCases() {
        return new Object[][] {{"en-US", "LanaPixel.fnt", "complete"},
                {"zh-CN", "Custom.fnt", "complete"}, {"zh-CN", "LanaPixel.fnt", "absent"},
                {"zh-CN", "LanaPixel.fnt", "corrupt"}, {"zh-CN", "LanaPixel.fnt", "missing-second"},
                {"zh-CN", "LanaPixel.fnt", "corrupt-second"}};
    }

    @Test(dataProvider = "fallbackCases")
    public void fallbackKeepsOldFontAndReleasesEveryAllocatedTexture(String language, String font, String mode) throws Exception {
        final Path output = Path.of(System.getProperty("forge.independent.testRoot"));
        final Path directory = Files.createTempDirectory(output, "font-" + mode + "-");
        Files.copy(skinDirectory().resolve("LanaPixel.fnt"), directory.resolve(font));
        Files.copy(skinDirectory().resolve("LanaPixel.png"), directory.resolve("LanaPixel.png"));
        if (!mode.equals("absent")) {
            if (mode.equals("corrupt")) {
                Files.writeString(directory.resolve(AdventureSkinLoader.PIXEL_FONT), "invalid");
            } else {
                Files.copy(skinDirectory().resolve(AdventureSkinLoader.PIXEL_FONT), directory.resolve(AdventureSkinLoader.PIXEL_FONT));
                Files.copy(skinDirectory().resolve("ForgeAdventurePixel-0.png"), directory.resolve("ForgeAdventurePixel-0.png"));
                if (mode.equals("corrupt-second")) {
                    Files.writeString(directory.resolve("ForgeAdventurePixel-1.png"), "not a PNG");
                    assertNull(ImageIO.read(directory.resolve("ForgeAdventurePixel-1.png").toFile()));
                } else if (!mode.equals("missing-second")) {
                    Files.copy(skinDirectory().resolve("ForgeAdventurePixel-1.png"), directory.resolve("ForgeAdventurePixel-1.png"));
                }
            }
        }
        final Path json = directory.resolve("skin.json");
        Files.writeString(json, "{\"BitmapFont\":{\"default\":{\"file\":\"" + font + "\"}}}");
        final boolean selected = AdventureSkinLoader.shouldUsePixelFont(language, font, new FileHandle(json.toFile()));
        assertEquals(selected, language.equals("zh-CN") && font.equals("LanaPixel.fnt") && !mode.equals("absent"));
        final com.badlogic.gdx.Files previous = Gdx.files;
        Gdx.files = mockFiles();
        final List<Texture> loaded = new ArrayList<>();
        final List<String> attempted = new ArrayList<>();
        try (var ignored = textures(loaded, attempted)) {
            final Skin skin = skin(language, new TextureAtlas());
            try {
                skin.load(new FileHandle(json.toFile()));
                assertEquals(skin.getFont("default").getData().lineHeight, 13f);
                if (mode.equals("corrupt-second")) {
                    assertEquals(attempted, java.util.Arrays.asList(
                            "ForgeAdventurePixel-0.png", "ForgeAdventurePixel-1.png", "LanaPixel.png"));
                    assertEquals(loaded.size(), 2, "Only page zero and the legacy page successfully constructed");
                    verify(loaded.get(0), times(1)).dispose();
                    verify(loaded.get(1), never()).dispose();
                }
                if (mode.equals("missing-second")) {
                    assertEquals(attempted, java.util.Collections.singletonList("LanaPixel.png"));
                }
            } finally {
                skin.dispose();
            }
            for (Texture texture : loaded) verify(texture, times(1)).dispose();
        } finally {
            Gdx.files = previous;
        }
    }
}
