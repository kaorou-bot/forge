package forge.adventure.util;

import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.scenes.scene2d.ui.Label;
import com.github.tommyettinger.textra.Font;
import com.github.tommyettinger.textra.TypingConfig;
import com.github.tommyettinger.textra.TypingLabel;

/** Keeps wrapping and typing-token indices in the same display string. */
public class AdventureTypingLabel extends TypingLabel {
    private float wrappedWidth = Float.NaN;

    public AdventureTypingLabel(String text, Label.LabelStyle style, Font font) {
        super(AdventureText.wrap(text), style, AdventureText.prepareFont(font));
    }

    public AdventureTypingLabel(String text, Font font, Color color) {
        super(AdventureText.wrap(text), AdventureText.prepareFont(font), color);
    }

    @Override
    public void setText(String text) {
        // The superclass preprocesses text before reaching the protected overload.
        super.setText(text == null ? "" : text);
    }

    @Override
    protected void setText(String text, boolean modifyOriginalText, boolean restart) {
        String displayText = AdventureText.wrap(text);
        if (modifyOriginalText) storedText = displayText;
        wrappedWidth = Float.NaN;
        super.setText(displayText, modifyOriginalText, restart);
    }

    @Override
    public void setIntermediateText(CharSequence text, boolean modifyOriginalText, boolean restart) {
        // Parser calls this after VAR/IF expansion and before registering EVENT/WAIT
        // indices. Adding breaks only in Font.markup would desynchronize those indices.
        super.setIntermediateText(AdventureText.wrap(text.toString()), modifyOriginalText, restart);
    }

    @Override
    public void layout() {
        // Ensure variable replacement and token registration have completed.
        super.getPrefHeight();
        float width = getWidth();
        if (style != null && style.background != null) {
            width -= style.background.getLeftWidth() + style.background.getRightWidth();
        }
        // Rebuild from parsed display text, not already wrapped glyphs: Textra's
        // regenerateLayout retains synthetic spaces from previous line breaks,
        // which can push visible glyphs past the new width after repeated reflows.
        // This does not restart typing or re-register/trigger any event tokens.
        if (wrap && (wrappedWidth != width || workingLayout.getTargetWidth() != width)) {
            workingLayout.setTargetWidth(width);
            font.markup(getIntermediateText().toString(), workingLayout.clear());
            invalidateHierarchy();
        } else if (!wrap) {
            super.layout();
        }
        wrappedWidth = width;
    }

    @Override
    public void act(float delta) {
        // These are display-only separators, not extra characters to wait for.
        // Scope the library's shared setting to this label's render-thread update.
        boolean existed = TypingConfig.INTERVAL_MULTIPLIERS_BY_CHAR.containsKey('\u200B');
        float previous = TypingConfig.INTERVAL_MULTIPLIERS_BY_CHAR.get('\u200B', 1f);
        TypingConfig.INTERVAL_MULTIPLIERS_BY_CHAR.put('\u200B', 0f);
        try {
            layout(); // Reflow before applying this frame's typing effects.
            super.act(delta);
        } finally {
            if (existed) TypingConfig.INTERVAL_MULTIPLIERS_BY_CHAR.put('\u200B', previous);
            else TypingConfig.INTERVAL_MULTIPLIERS_BY_CHAR.remove('\u200B', 1f);
        }
    }
}
