package forge.adventure.util;

import com.badlogic.gdx.assets.loaders.SkinLoader;
import com.badlogic.gdx.assets.loaders.resolvers.AbsoluteFileHandleResolver;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.g2d.TextureRegion;
import com.badlogic.gdx.scenes.scene2d.ui.Skin;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonValue;

/** Selects the bundled Chinese pixel face without replacing custom world fonts. */
public final class AdventureSkinLoader extends SkinLoader {
    static final String PIXEL_FONT = "ForgeAdventurePixel.fnt";
    private final String language;

    public AdventureSkinLoader(String language) {
        super(new AbsoluteFileHandleResolver());
        this.language = language;
    }

    static boolean shouldUsePixelFont(String language, String fontFile, FileHandle skinFile) {
        return "zh-CN".equals(language) && "LanaPixel.fnt".equals(fontFile)
                && skinFile.sibling(PIXEL_FONT).exists();
    }

    private static BitmapFont loadPixelFont(FileHandle file, JsonValue settings) {
        final BitmapFont.BitmapFontData data = new BitmapFont.BitmapFontData(file, settings.getBoolean("flip", false));
        final Array<FileHandle> pages = new Array<>();
        for (String path : data.getImagePaths()) {
            final FileHandle page = new FileHandle(path);
            if (!page.exists()) {
                throw new GdxRuntimeException("Missing adventure font page: " + path);
            }
            pages.add(page);
        }
        final Array<Texture> textures = new Array<>();
        final Array<TextureRegion> regions = new Array<>();
        boolean transferred = false;
        try {
            for (FileHandle page : pages) {
                final Texture texture = new Texture(page);
                textures.add(texture);
                texture.setFilter(Texture.TextureFilter.Nearest, Texture.TextureFilter.Nearest);
                regions.add(new TextureRegion(texture));
            }
            final BitmapFont font = new BitmapFont(data, regions, settings.getBoolean("useIntegerPositions", true));
            data.markupEnabled = settings.getBoolean("markupEnabled", false);
            final float scaledSize = settings.getFloat("scaledSize", -1f);
            if (scaledSize != -1f) {
                data.setScale(scaledSize / font.getCapHeight());
            }
            font.setOwnsTexture(true);
            transferred = true; // Skin.dispose now owns all pages through this font.
            return font;
        } finally {
            if (!transferred) {
                // In particular, release page zero if decoding a later PNG fails.
                for (Texture texture : textures) {
                    texture.dispose();
                }
            }
        }
    }

    @Override
    protected Skin newSkin(TextureAtlas atlas) {
        return new Skin(atlas) {
            @Override
            protected Json getJsonLoader(final FileHandle skinFile) {
                final Json json = super.getJsonLoader(skinFile);
                final Json.Serializer<BitmapFont> original = json.getSerializer(BitmapFont.class);
                json.setSerializer(BitmapFont.class, new Json.ReadOnlySerializer<BitmapFont>() {
                    @Override
                    public BitmapFont read(Json reader, JsonValue data, Class type) {
                        final String path = data.getString("file");
                        if (shouldUsePixelFont(language, path, skinFile)) {
                            try {
                                return loadPixelFont(skinFile.sibling(PIXEL_FONT), data);
                            } catch (RuntimeException e) {
                                // Old or incomplete resource packages must still open Adventure.
                                System.err.println("Adventure pixel font unavailable; using LanaPixel: " + e.getMessage());
                            }
                        }
                        return original.read(reader, data, type);
                    }
                });
                return json;
            }
        };
    }
}
