package forge.adventure.util;

import com.github.tommyettinger.textra.Font;

/** Display-only break opportunities for Textra 0.8.2, which otherwise breaks at spaces. */
public final class AdventureText {
    private static final char BREAK = '\u200B';
    private static final String OPEN = "（［｛〈《「『【〔〖〘〚‘“([{<";
    private static final String CLOSE = "，。！？；：、）］｝〉》」』】〕〗〙〛’”…,!.?;:)]}>%％";

    private AdventureText() { }

    public static Font prepareFont(Font font) {
        // Some custom/older world fonts omit U+200B. Never render a missing-glyph box
        // or give the invisible break a space's advance. Reuse a texture; allocate no GPU resource.
        if (!font.mapping.containsKey(BREAK)) {
            Font.GlyphRegion space = font.mapping.get(' ');
            if (space != null) {
                Font.GlyphRegion invisible = new Font.GlyphRegion(space);
                invisible.setRegionWidth(0);
                invisible.setRegionHeight(0);
                invisible.offsetX = 0;
                invisible.offsetY = 0;
                invisible.xAdvance = 0;
                font.mapping.put(BREAK, invisible);
            }
        }
        return font;
    }

    public static String wrap(String text) {
        if (text == null || text.isEmpty()) return text == null ? "" : text;
        StringBuilder result = new StringBuilder(text.length());
        int previous = -1;
        for (int i = 0; i < text.length();) {
            int cp = text.codePointAt(i);
            // Leave Textra formatting, inline images, and TypingLabel tokens intact.
            if (cp == '[' || cp == '{') {
                if (cp == '[' && i + 1 < text.length() && text.charAt(i + 1) == '[') {
                    result.append("[[");
                    previous = '[';
                    i += 2;
                    continue;
                }
                int end = text.indexOf(cp == '[' ? ']' : '}', i + 1);
                if (end >= 0) {
                    result.append(text, i, end + 1);
                    if (cp == '[' && i + 1 < end && text.charAt(i + 1) == '+') previous = -1;
                    i = end + 1;
                    continue;
                }
            }
            if (previous >= 0 && (isCjk(previous) || isCjk(cp))
                    && !Character.isWhitespace(previous) && !Character.isWhitespace(cp)
                    && previous != BREAK && cp != BREAK
                    && OPEN.indexOf(previous) < 0 && CLOSE.indexOf(cp) < 0) {
                result.append(BREAK);
            }
            result.appendCodePoint(cp);
            previous = cp;
            i += Character.charCount(cp);
        }
        return result.toString();
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x3400 && cp <= 0x9FFF) || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0x20000 && cp <= 0x323AF) || (cp >= 0x3040 && cp <= 0x30FF)
                || (cp >= 0xAC00 && cp <= 0xD7AF) || (cp >= 0x3000 && cp <= 0x303F);
    }
}
