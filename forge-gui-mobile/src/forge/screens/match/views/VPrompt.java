package forge.screens.match.views;

import forge.Forge;
import org.apache.commons.lang3.StringUtils;

import com.badlogic.gdx.utils.Align;

import forge.Graphics;
import forge.assets.FSkinColor;
import forge.assets.FSkinColor.Colors;
import forge.assets.FSkinFont;
import forge.assets.TextRenderer;
import forge.card.CardZoom;
import forge.game.card.CardView;
import forge.menu.FMagnifyView;
import forge.toolbox.FButton;
import forge.toolbox.FButton.Corner;
import forge.toolbox.FContainer;
import forge.toolbox.FDisplayObject;
import forge.toolbox.FEvent.FEventHandler;
import forge.util.TextBounds;
import forge.util.Utils;

public class VPrompt extends FContainer {
    public static final float HEIGHT = Utils.AVG_FINGER_HEIGHT;
    public static final float BTN_WIDTH = HEIGHT * 1.5f;
    public static final float PADDING = Utils.scale(2);
    public static final FSkinFont FONT = FSkinFont.get(14);
    public static FSkinColor getBackColor() {
        if (Forge.isMobileAdventureMode)
            return FSkinColor.get(Colors.ADV_CLR_THEME2);
        return FSkinColor.get(Colors.CLR_THEME2);
    }
    public static FSkinColor getForeColor() {
        if (Forge.isMobileAdventureMode)
            return FSkinColor.get(Colors.ADV_CLR_TEXT);
        return FSkinColor.get(Colors.CLR_TEXT);
    }

    private final FButton btnOk, btnCancel;
    private final MessageLabel lblMessage;
    private final ClockLabel lblClock;
    private String message;
    private String operationClock = "";
    private FMagnifyView clockDetails;
    private static final float CLOCK_HEIGHT = Utils.scale(24);
    public void setOperationClock(final String text) {
        operationClock = text;
        lblClock.setVisible(!text.isEmpty());
        if (clockDetails != null) {
            if (text.isEmpty()) {
                clockDetails.hide();
                clockDetails = null;
            } else if (clockDetails.isVisible()) {
                clockDetails.updateText(getOperationClockDetails());
            } else {
                clockDetails = null;
            }
        }
    }
    private String getOperationClockDetails() {
        return forge.util.Localizer.getInstance().getMessage("lblOperationClock")
                + "\n" + operationClock.replace("  |  ", "\n");
    }
    public float getPreferredHeight() { return HEIGHT + (operationClock.isEmpty() ? 0 : CLOCK_HEIGHT); }
    private CardView card;

    public void setCardView(final CardView card) {
        this.card = card;
    }

    public VPrompt(String okText, String cancelText, FEventHandler okCommand, FEventHandler cancelCommand) {
        lblClock = add(new ClockLabel());
        lblClock.setVisible(false);
        lblMessage = add(new MessageLabel());
        lblMessage.setLeft(BTN_WIDTH);
        lblMessage.setHeight(HEIGHT);
        btnOk = add(new FButton(okText, okCommand));
        btnCancel = add(new FButton(cancelText, cancelCommand));
        btnOk.setSize(BTN_WIDTH, HEIGHT);
        btnCancel.setSize(BTN_WIDTH, HEIGHT);
        btnOk.setCorner(Corner.BottomLeft);
        btnCancel.setCorner(Corner.BottomRight);
        btnOk.setEnabled(false); //disable buttons until first input queued
        btnCancel.setEnabled(false);
    }

    public FButton getBtnOk() {
        return btnOk;
    }

    public FButton getBtnCancel() {
        return btnCancel;
    }

    public String getMessage() {
        return message;
    }
    public void setMessage(String message0) {
        message = message0;
        card = null;
    }
    public void setMessage(String message0, CardView card0) {
        message = message0;
        card = card0;
    }

    /** Flashes animation on input panel if play is currently waiting on input. */
    public void remind() {
        //SDisplayUtil.remind(view);
    }

    @Override
    protected void doLayout(float width, float height) {
        final float clockHeight = operationClock.isEmpty() ? 0 : CLOCK_HEIGHT;
        lblClock.setBounds(0, 0, width, clockHeight);
        lblMessage.setTop(clockHeight);
        btnOk.setTop(clockHeight);
        btnCancel.setTop(clockHeight);
        lblMessage.setWidth(width - 2 * BTN_WIDTH);
        if (Forge.reversedPrompt) {
            btnOk.setCorner(Corner.BottomRight);
            btnCancel.setCorner(Corner.BottomLeft);
            btnOk.setLeft(lblMessage.getRight());
        } else {
            btnOk.setCorner(Corner.BottomLeft);
            btnCancel.setCorner(Corner.BottomRight);
            btnCancel.setLeft(lblMessage.getRight());
        }
    }

    @Override
    protected void drawBackground(Graphics g) {
        g.fillRect(getBackColor(), 0, 0, getWidth(), getHeight());
    }
    
    private class MessageLabel extends FDisplayObject {
        private final TextRenderer renderer = new TextRenderer();

        @Override
        public boolean tap(float x, float y, int count) {
            //if not enough room for prompt at given size, show magnify view
            float maxWidth = getWidth() - 2 * PADDING;
            float maxHeight = getHeight() - 2 * PADDING;
            TextBounds textBounds = renderer.getWrappedBounds(message, FONT, maxWidth);
            if (textBounds.height > maxHeight) {
                FMagnifyView.show(this, message, getForeColor(), getBackColor(), FONT, false);
            }
            return true;
        }

        @Override
        public boolean fling(float x, float y) {
            if (card != null) {
                CardZoom.show(card);
            }
            return true;
        }

        @Override
        public boolean longPress(float x, float y) {
            if (card != null) {
                CardZoom.show(card);
            }
            return true;
        }

        @Override
        public void draw(Graphics g) {
            if (!StringUtils.isEmpty(message)) {
                float x = PADDING;
                float y = PADDING;
                float w = getWidth() - 2 * PADDING;
                float h = getHeight() - 2 * PADDING;
                renderer.drawText(g, message, FONT, getForeColor(), x, y, w, h, y, h, true, Align.center, true);
            }
        }
    }

    /** Separate from the decision text: eight clocks must never shrink an action prompt. */
    private class ClockLabel extends FDisplayObject {
        private final FSkinFont font = FSkinFont.get(13);
        private String cachedText = "";
        private String fittedText = "";
        private float cachedWidth = -1;

        @Override public boolean tap(float x, float y, int count) {
            if (operationClock.isEmpty()) return true;
            if (clockDetails != null) clockDetails.hide();
            clockDetails = FMagnifyView.showUpdatable(this, getOperationClockDetails(),
                    getForeColor(), getBackColor(), FONT, false);
            return true;
        }

        @Override public void draw(Graphics g) {
            final float width = Math.max(0, getWidth() - 2 * PADDING);
            final String text = forge.util.Localizer.getInstance().getMessage("lblOperationClockExpand") + "  " + operationClock;
            if (!text.equals(cachedText) || cachedWidth != width) {
                cachedText = text;
                cachedWidth = width;
                fittedText = text;
                if (font.getBounds(fittedText).width > width) {
                    int end = text.length();
                    while (end > 0 && font.getBounds(text.substring(0, end) + "…").width > width) {
                        end = text.offsetByCodePoints(end, -1);
                    }
                    fittedText = end == 0 ? "" : text.substring(0, end) + "…";
                }
            }
            g.drawText(fittedText, font, getForeColor(), PADDING, 0, width, getHeight(), false, Align.left, true);
            g.drawLine(1, getForeColor(), 0, getHeight() - 1, getWidth(), getHeight() - 1);
        }
    }
}
