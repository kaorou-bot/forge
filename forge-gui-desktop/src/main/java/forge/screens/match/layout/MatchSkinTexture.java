package forge.screens.match.layout;

import com.google.gson.JsonElement;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Set;
import java.util.function.Function;

/** Bounded, package-local texture. Nine-slice coordinates are source pixels: top,right,bottom,left. */
public record MatchSkinTexture(BufferedImage image, Mode mode, int top, int right, int bottom, int left) {
    public enum Mode { STRETCH, CONTAIN, COVER, TILE, NINE_SLICE }

    static MatchSkinTexture read(JsonElement value, Function<String, BufferedImage> loader) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return new MatchSkinTexture(loader.apply(value.getAsString()), Mode.STRETCH, 0, 0, 0, 0);
        }
        final var json = value.getAsJsonObject();
        MatchUiLayout.keys(json, Set.of("path", "mode", "slices"));
        final var image = loader.apply(json.get("path").getAsString());
        final var mode = json.has("mode") ? Mode.valueOf(json.get("mode").getAsString()) : Mode.STRETCH;
        int[] slices = new int[4];
        if (json.has("slices")) {
            final var a = json.getAsJsonArray("slices");
            if (a.size() != 4 || mode != Mode.NINE_SLICE) { throw new IllegalArgumentException("slices requires NINE_SLICE and four integers"); }
            for (int i = 0; i < 4; i++) { slices[i] = (int) MatchSkinTheme.number(a.get(i), 0, 16384, true); }
        }
        if (mode == Mode.NINE_SLICE && (!json.has("slices") || slices[0] + slices[2] >= image.getHeight()
                || slices[1] + slices[3] >= image.getWidth())) {
            throw new IllegalArgumentException("Nine-slice borders must leave a nonempty source center");
        }
        return new MatchSkinTexture(image, mode, slices[0], slices[1], slices[2], slices[3]);
    }

    public void paint(Graphics2D source, int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) { return; }
        final var g = (Graphics2D) source.create(x, y, width, height);
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            final int iw = image.getWidth(), ih = image.getHeight();
            switch (mode) {
                case STRETCH -> g.drawImage(image, 0, 0, width, height, null);
                case TILE -> { g.setPaint(new java.awt.TexturePaint(image, new java.awt.Rectangle(0, 0, iw, ih))); g.fillRect(0, 0, width, height); }
                case CONTAIN, COVER -> {
                    final double scale = mode == Mode.CONTAIN ? Math.min((double) width / iw, (double) height / ih)
                            : Math.max((double) width / iw, (double) height / ih);
                    final int w = Math.max(1, (int) Math.round(iw * scale)), h = Math.max(1, (int) Math.round(ih * scale));
                    g.drawImage(image, (width - w) / 2, (height - h) / 2, w, h, null);
                }
                case NINE_SLICE -> {
                    final double sx = Math.min(1, (double) width / Math.max(1, left + right));
                    final double sy = Math.min(1, (double) height / Math.max(1, top + bottom));
                    final int[] dx = {0, (int) (left * sx), width - (int) (right * sx), width};
                    final int[] dy = {0, (int) (top * sy), height - (int) (bottom * sy), height};
                    final int[] ix = {0, left, iw - right, iw}, iy = {0, top, ih - bottom, ih};
                    for (int row = 0; row < 3; row++) { for (int col = 0; col < 3; col++) {
                        g.drawImage(image, dx[col], dy[row], dx[col + 1], dy[row + 1], ix[col], iy[row], ix[col + 1], iy[row + 1], null);
                    } }
                }
            }
        } finally { g.dispose(); }
    }
}
