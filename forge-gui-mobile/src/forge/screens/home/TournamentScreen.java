package forge.screens.home;

import com.badlogic.gdx.utils.Align;
import forge.Graphics;
import forge.assets.FSkinColor;
import forge.assets.FSkinFont;
import forge.card.CardRenderer;
import forge.card.CardZoom;
import forge.deck.Deck;
import forge.deck.FDeckViewer;
import forge.item.PaperCard;
import forge.deck.tournament.TournamentApi;
import forge.deck.tournament.TournamentBrowser;
import forge.gui.FThreads;
import forge.gui.util.SOptionPane;
import forge.screens.FScreen;
import forge.toolbox.*;
import forge.util.Utils;
import java.util.function.Consumer;

/** Forge-native, touch-sized browser; the same controls reflow in portrait and landscape. */
public final class TournamentScreen extends FScreen implements TournamentBrowser.View {
    private TournamentBrowser browser;
    private final FComboBox<TournamentApi.Format> format = add(new FComboBox<>());
    private final FComboBox<String> mode = add(new FComboBox<>());
    private final FButton search = add(new FButton(TournamentBrowser.text("search", "Search")));
    private final FButton refresh = add(new FButton(TournamentBrowser.text("refresh", "Refresh")));
    private final FButton next = add(new FButton(TournamentBrowser.text("moreEvents", "More events")));
    private final FButton back = add(new FButton(TournamentBrowser.text("backEvents", "Back to events")));
    private final FTextArea status = add(new FTextArea(false));
    private final FList<String> list = add(new FList<>());
    private boolean rendering;
    private java.util.List<TournamentApi.Format> knownFormats;
    private java.util.List<String> knownRows;
    private float eventScroll;
    private boolean wasInEvent;
    private FScreen activeViewer;

    public TournamentScreen() {
        super(TournamentBrowser.title());
        for (String label : TournamentBrowser.modes()) mode.addItem(label);
        format.setChangedHandler(e -> change());
        mode.setChangedHandler(e -> change());
        refresh.setCommand(e -> browser.refresh());
        next.setCommand(e -> browser.more());
        back.setCommand(e -> browser.back());
        search.setCommand(e -> {
            TournamentBrowser active = browser;
            FThreads.invokeInBackgroundThread(() -> {
                String value = SOptionPane.showInputDialog(TournamentBrowser.text("searchPrompt", "Deck, player or event; empty clears the search"),
                        TournamentBrowser.title(), null, active.query);
                if (value != null) FThreads.invokeInEdtLater(() -> { if (browser == active) active.search(value); });
            });
        });
        list.setListItemRenderer(new FList.ListItemRenderer<>() {
            @Override public float getItemHeight() { return Utils.AVG_FINGER_HEIGHT * 1.7f; }
            @Override public boolean tap(Integer i, String value, float x, float y, int count) {
                browser.select(i); return true;
            }
            @Override public boolean showMenu(Integer i, String value, FDisplayObject owner, float x, float y) {
                PaperCard card = browser == null ? null : browser.rowCard(i);
                if (card == null) return false;
                CardZoom.show(card);
                return true;
            }
            @Override public void drawValue(Graphics g, Integer i, String value, FSkinFont font,
                    FSkinColor fore, FSkinColor background, boolean pressed, float x, float y, float w, float h) {
                if (browser != null && browser.hasPictures()) {
                    float imageWidth = h * (browser.inEvent() ? 1.1f : .7f), gap = Utils.scale(6);
                    PaperCard card = browser.rowCard(i);
                    if (card != null) {
                        if (browser.inEvent()) {
                            // Like Cardlist's cover: show the illustration, not tiny card rules.
                            forge.assets.FImage art = CardRenderer.getCardArt(card);
                            if (art != null && art.getWidth() > 0 && art.getHeight() > 0) {
                                float scale = Math.max(imageWidth / art.getWidth(), h / art.getHeight());
                                if (g.startClip(x, y, imageWidth, h)) {
                                    g.drawImage(art, x + (imageWidth - art.getWidth() * scale) / 2,
                                            y + (h - art.getHeight() * scale) / 2,
                                            art.getWidth() * scale, art.getHeight() * scale);
                                }
                                g.endClip();
                            }
                        } else {
                            CardRenderer.drawCard(g, card, x, y, imageWidth, h, CardRenderer.CardStackPosition.Top);
                        }
                    } else {
                        g.drawText("?", font, fore, x, y, imageWidth, h, false, Align.center, true);
                    }
                    x += imageWidth + gap; w -= imageWidth + gap;
                }
                String[] lines = value.split("\n", 2);
                // Portrait needs wrapping: fitting a whole event/title on one line makes it unreadably small.
                g.drawText(lines[0], font, fore, x, y, w, h / 2, true, Align.left, true);
                if (lines.length > 1) g.drawText(lines[1], FSkinFont.get(12), fore, x, y + h / 2,
                        w, h / 2, true, Align.left, true);
            }
        });
    }
    @Override public void showDeck(Deck deck, Runnable save) {
        // The native viewer shares the editor's card manager, but has no edit or save actions.
        // Switching via the landscape sidebar does not close this screen. Keep the result
        // for manual preview on return, instead of stealing navigation after a late response.
        if (forge.Forge.getCurrentScreen() == this) {
            FDeckViewer.show(deck, save);
            activeViewer = forge.Forge.getCurrentScreen();
        }
    }
    @Override public void message(String text) {
        FScreen current = forge.Forge.getCurrentScreen();
        if (current == this || current == activeViewer) FOptionPane.showMessageDialog(text);
    }
    @Override public void onActivate() {
        super.onActivate();
        if (browser == null) { browser = new TournamentBrowser(this); browser.start(); }
    }
    @Override public void onClose(Consumer<Boolean> callback) {
        if (callback != null && browser != null && browser.inEvent()) {
            browser.back();
            callback.accept(false); // consume normal back; null is actual application disposal
            return;
        }
        if (browser != null) browser.close();
        browser = null; knownFormats = null; knownRows = null; activeViewer = null;
        super.onClose(callback);
    }
    private void change() {
        if (!rendering && browser != null)
            browser.filter(format.getSelectedIndex(), mode.getSelectedIndex());
    }
    @Override public void render(TournamentBrowser b) {
        rendering = true;
        if (knownFormats != b.formats) {
            knownFormats = b.formats; format.removeAllItems();
            for (TournamentApi.Format f : b.formats) format.addItem(f);
        }
        format.setSelectedIndex(b.formats.isEmpty() ? -1 : b.formatIndex); mode.setSelectedIndex(b.mode);
        format.setEnabled(!b.busy); mode.setEnabled(!b.busy);
        search.setEnabled(!b.busy && b.mode == 0 && !b.inEvent());
        refresh.setEnabled(!b.busy);
        if (knownRows != b.rows) {
            float scroll = list.getScrollTop();
            if (!wasInEvent && b.inEvent()) eventScroll = scroll;
            knownRows = b.rows; list.setListData(b.rows);
            list.setScrollTop(b.rowChange == TournamentBrowser.RowChange.RETURN_TO_EVENTS ? eventScroll
                    : b.rowChange == TournamentBrowser.RowChange.APPEND ? scroll : 0);
        }
        wasInEvent = b.inEvent();
        status.setText(b.status); status.setScrollTop(0);
        next.setVisible(b.mode == 0 && !b.inEvent());
        next.setEnabled(!b.busy && b.canLoadMore());
        back.setVisible(b.inEvent()); back.setEnabled(true);
        rendering = false; revalidate();
    }
    @Override protected void doLayout(float startY, float width, float height) {
        float gap = Utils.scale(5), row = Utils.AVG_FINGER_HEIGHT;
        float w = width - gap * 2, y = startY + gap;
        format.setBounds(gap, y, (w - gap) * .45f, row);
        mode.setBounds(gap + (w - gap) * .45f + gap, y, (w - gap) * .55f, row);
        y += row + gap;
        search.setBounds(gap, y, (w - gap) / 2, row);
        refresh.setBounds(gap + (w + gap) / 2, y, (w - gap) / 2, row);
        y += row + gap;
        float statusHeight = Math.min(row * 1.7f, Math.max(row, (height - startY) * .18f));
        status.setBounds(gap, y, w, statusHeight);
        y += statusHeight + gap;
        float bottom = height - row - gap;
        list.setBounds(gap, y, w, Math.max(row, bottom - y - gap));
        next.setBounds(gap, bottom, w, row);
        back.setBounds(gap, bottom, w, row);
    }
}
