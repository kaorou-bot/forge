package forge.screens.home;

import forge.deck.tournament.TournamentApi;
import forge.deck.tournament.TournamentBrowser;
import forge.deck.Deck;
import forge.deck.DeckSection;
import forge.deckchooser.FDeckViewer;
import forge.item.PaperCard;
import forge.CachedCardImage;
import forge.game.card.CardView;
import forge.gui.FThreads;
import forge.toolbox.*;
import net.miginfocom.swing.MigLayout;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/** Uses the active Forge skin rather than a separate web UI. */
public final class TournamentPanel extends FSkin.SkinnedPanel implements TournamentBrowser.View {
    private TournamentBrowser browser;
    private final FComboBox<TournamentApi.Format> format = new FComboBox<>();
    private final FComboBox<String> mode = new FComboBox<>(TournamentBrowser.modes());
    private final FList<String> list = new FList<>() {
        @Override public String getToolTipText(MouseEvent event) {
            int index = locationToIndex(event.getPoint());
            if (index < 0 || !getCellBounds(index, index).contains(event.getPoint())) return null;
            // Only our fixed wrapper is markup; all server-provided text is escaped.
            String text = getModel().getElementAt(index).replace("&", "&amp;")
                    .replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>");
            return "<html><div style='width:360px'>" + text + "</div></html>";
        }
    };
    private final FTextArea status = new FTextArea();
    private final FScrollPane listScroll = new FScrollPane(list, true);
    private final FButton search = new FButton(TournamentBrowser.text("search", "Search"));
    private final FButton refresh = new FButton(TournamentBrowser.text("refresh", "Refresh"));
    private final FButton next = new FButton(TournamentBrowser.text("moreEvents", "More events"));
    private final FButton open = new FButton(TournamentBrowser.text("open", "Open"));
    private final FButton back = new FButton(TournamentBrowser.text("backEvents", "Back to events"));
    private final FTextField query = new FTextField.Builder().ghostText(TournamentBrowser.text("searchPrompt", "Deck, player or event; empty clears search")).maxLength(200).build();
    private boolean rendering;
    private int eventSelection = -1;
    private int eventScroll;
    private boolean showingEvent;
    private final Timer hold = new Timer(600, e -> showUsageCard());
    private final java.util.Map<PaperCard, CachedCardImage> thumbnails = new java.util.HashMap<>();
    private java.util.List<TournamentApi.Format> knownFormats;
    private java.util.List<String> knownRows;

    public TournamentPanel() {
        setOpaque(false);
        DefaultListCellRenderer formatRenderer = new DefaultListCellRenderer();
        formatRenderer.putClientProperty("html.disable", Boolean.TRUE);
        format.setRenderer((items, value, index, selected, focus) -> {
            Component row = formatRenderer.getListCellRendererComponent(items, value, index, selected, focus);
            formatRenderer.setBorder(BorderFactory.createEmptyBorder(4, 3, 4, 3));
            return row;
        });
        setLayout(new MigLayout("insets 16, gap 10, fill, wrap 1", "[grow,fill]", "[][][][grow,fill][]"));
        LblHeader title = new LblHeader(TournamentBrowser.title());
        add(title, "h 42!, growx");
        JPanel filters = new FSkin.SkinnedPanel(new MigLayout("insets 0, fillx", "[grow,fill][grow,fill][]", "[]"));
        filters.setOpaque(false);
        filters.add(format); filters.add(mode); filters.add(refresh);
        add(filters, "growx");
        JPanel searchBar = new FSkin.SkinnedPanel(new MigLayout("insets 0, fillx", "[grow,fill][]", "[]"));
        searchBar.setOpaque(false);
        searchBar.add(query); searchBar.add(search);
        add(searchBar, "growx");
        FSkin.SkinnedPanel content = new FSkin.SkinnedPanel(new MigLayout("insets 10, gap 8, fill, wrap 1", "[grow,fill]", "[][grow,fill]"));
        content.setBackground(FSkin.getColor(FSkin.Colors.CLR_THEME2));
        status.setFont(FSkin.getRelativeFont(13));
        content.add(new FScrollPane(status, false), "growx, h 62:78:100");
        content.add(listScroll, "grow, push");
        add(content, "grow, push");
        JPanel actions = new FSkin.SkinnedPanel(new MigLayout("insets 0, fillx, hidemode 3", "[][][grow][]", "[]"));
        actions.setOpaque(false);
        actions.add(back); actions.add(next); JPanel spacer = new JPanel(); spacer.setOpaque(false); actions.add(spacer, "growx");
        actions.add(open);
        add(actions, "growx");
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setToolTipText(""); // Register dynamic, safely escaped full-text tooltips.
        // Disable Swing's HTML interpretation of untrusted server strings.
        list.setCellRenderer((items, value, index, selected, focus) -> {
            FTextArea row = new FTextArea(value);
            row.setFont(FSkin.getRelativeFont(14));
            row.setForeground(FSkin.getColor(FSkin.Colors.CLR_TEXT));
            row.setOpaque(true);
            row.setBackground(FSkin.getColor(selected ? FSkin.Colors.CLR_ACTIVE : FSkin.Colors.CLR_THEME2));
            row.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            JPanel panel = new JPanel(new BorderLayout(8, 0));
            panel.setOpaque(true); panel.setBackground(row.getBackground());
            if (browser != null && browser.hasPictures()) {
                PaperCard card = browser.rowCard(index);
                TournamentBrowser requestedBrowser = browser;
                JLabel picture = new JLabel("?", SwingConstants.CENTER);
                picture.setPreferredSize(new Dimension(browser.inEvent() ? 100 : 54, 76));
                if (card != null) {
                    CachedCardImage cached = thumbnails.computeIfAbsent(card, c ->
                            new CachedCardImage(CardView.getCardForUi(c), null, 240, 336) {
                                @Override public void onImageFetched() {
                                    FThreads.invokeInEdtLater(() -> { if (browser == requestedBrowser) list.repaint(); });
                                }
                            });
                    java.awt.image.BufferedImage image = cached.getImage();
                    if (image != null) {
                        picture.setText("");
                        picture.setIcon(browser.inEvent() ? coverIcon(image)
                                : new ImageIcon(image.getScaledInstance(54, 76, Image.SCALE_SMOOTH)));
                    }
                }
                panel.add(picture, BorderLayout.WEST);
            }
            panel.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, selected ? 3 : 1, 1, 1, FSkin.getColor(selected ? FSkin.Colors.CLR_ACTIVE : FSkin.Colors.CLR_THEME).getColor()),
                    BorderFactory.createEmptyBorder(6, 8, 6, 8)));
            panel.add(row, BorderLayout.CENTER);
            return panel;
        });
        list.setFixedCellHeight(100);
        list.setFixedCellWidth(1);
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "open-row");
        list.getActionMap().put("open-row", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { activateSelection(); }
        });
        hold.setRepeats(false);
        format.addActionListener(e -> change()); mode.addActionListener(e -> change());
        refresh.addActionListener(e -> { if (browser != null) browser.refresh(); });
        next.addActionListener(e -> { if (browser != null) browser.more(); });
        open.addActionListener(e -> activateSelection());
        list.addListSelectionListener(e -> open.setEnabled(browser != null && !browser.busy
                && browser.mode == 0 && list.getSelectedIndex() >= 0));
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2 && i >= 0
                        && list.getCellBounds(i, i).contains(e.getPoint())) {
                    list.setSelectedIndex(i);
                    activateSelection();
                }
            }
            @Override public void mousePressed(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (SwingUtilities.isLeftMouseButton(e) && i >= 0 && list.getCellBounds(i, i).contains(e.getPoint())) {
                    list.setSelectedIndex(i);
                    if (browser != null && browser.mode != 0 && !browser.busy) hold.restart();
                }
            }
            @Override public void mouseReleased(MouseEvent e) { hold.stop(); }
            @Override public void mouseExited(MouseEvent e) { hold.stop(); }
        });
        list.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) { hold.stop(); }
        });
        back.addActionListener(e -> { if (browser != null) browser.back(); });
        search.addActionListener(e -> submitSearch());
        query.addActionListener(e -> submitSearch());
    }

    public void activate() {
        if (browser != null) return;
        knownFormats = null; knownRows = null; showingEvent = false;
        eventSelection = -1; eventScroll = 0; query.setText("");
        list.setListData(new String[0]);
        browser = new TournamentBrowser(this);
        browser.start();
    }

    public void deactivate() {
        hold.stop();
        TournamentBrowser old = browser;
        browser = null;
        if (old != null) old.close();
        thumbnails.clear();
    }

    private void submitSearch() {
        if (browser != null && !browser.busy && browser.mode == 0 && !browser.inEvent()) browser.search(query.getText());
    }

    private void activateSelection() {
        if (browser == null || browser.busy) return;
        if (browser.mode != 0) { showUsageCard(); return; }
        if (!browser.inEvent()) {
            eventSelection = list.getSelectedIndex();
            eventScroll = listScroll.getVerticalScrollBar().getValue();
        }
        browser.select(list.getSelectedIndex());
    }

    @Override public void showDeck(Deck deck, Runnable save) {
        if (browser != null && isShowing()) FDeckViewer.show(deck, save);
    }
    @Override public void message(String text) {
        if (browser != null && isShowing()) FOptionPane.showMessageDialog(text);
    }
    private static Icon coverIcon(java.awt.image.BufferedImage image) {
        // Cardlist's portrait fallback framing: retain the upper illustration, exclude rules.
        return new Icon() {
            @Override public int getIconWidth() { return 100; }
            @Override public int getIconHeight() { return 76; }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                int w = image.getWidth(), h = image.getHeight();
                double rw = w * .76, rh = h * .32;
                double scale = Math.max(100 / rw, 76 / rh);
                double cx = w * .5, cy = h * .33;
                g.drawImage(image, x, y, x + 100, y + 76,
                        (int) (cx - 50 / scale), (int) (cy - 38 / scale),
                        (int) (cx + 50 / scale), (int) (cy + 38 / scale), null);
            }
        };
    }
    private void showUsageCard() {
        hold.stop();
        if (browser == null || browser.busy || !isShowing()) return;
        PaperCard card = browser.rowCard(list.getSelectedIndex());
        if (card == null) return;
        Deck oneCard = new Deck(card.getName());
        oneCard.getOrCreate(DeckSection.Main).add(card);
        // Native read-only viewer includes rules text, card image, and alternate-face controls.
        FDeckViewer.show(oneCard);
    }
    private void change() {
        if (!rendering && browser != null) browser.filter(format.getSelectedIndex(), mode.getSelectedIndex());
    }

    private void restoreListPosition(TournamentBrowser b, int selection, int scroll) {
        if (selection >= 0 && selection < list.getModel().getSize()) list.setSelectedIndex(selection);
        java.util.List<String> rows = b.rows;
        FThreads.invokeInEdtLater(() -> {
            if (browser == b && b.rows == rows && !b.inEvent()) {
                listScroll.getVerticalScrollBar().setValue(scroll);
            }
        });
    }

    @Override public void render(TournamentBrowser b) {
        if (browser != b) return;
        hold.stop();
        rendering = true;
        if (knownFormats != b.formats) {
            knownFormats = b.formats; format.removeAllItems();
            for (TournamentApi.Format f : b.formats) format.addItem(f);
        }
        format.setSelectedIndex(b.formats.isEmpty() ? -1 : b.formatIndex); mode.setSelectedIndex(b.mode);
        format.setEnabled(!b.busy); mode.setEnabled(!b.busy);
        refresh.setEnabled(!b.busy); search.setEnabled(!b.busy && b.mode == 0 && !b.inEvent());
        if (knownRows != b.rows) {
            int selection = list.getSelectedIndex();
            int scroll = listScroll.getVerticalScrollBar().getValue();
            knownRows = b.rows; thumbnails.clear();
            list.setListData(b.rows.toArray(new String[0]));
            if (b.rowChange == TournamentBrowser.RowChange.APPEND && !b.inEvent()) {
                restoreListPosition(b, selection, scroll);
            }
        }
        boolean returning = showingEvent && !b.inEvent() && b.rowChange == TournamentBrowser.RowChange.RETURN_TO_EVENTS;
        showingEvent = b.inEvent();
        if (returning) restoreListPosition(b, eventSelection, eventScroll);
        status.setText(b.status); status.setCaretPosition(0);
        query.setEnabled(!b.busy && b.mode == 0 && !b.inEvent());
        if (!query.hasFocus()) query.setText(b.query);
        open.setText(TournamentBrowser.text(b.inEvent() ? "view" : "open", "Open"));
        list.setEnabled(!b.busy);
        next.setVisible(b.mode == 0 && !b.inEvent()); next.setEnabled(!b.busy && b.canLoadMore());
        open.setEnabled(!b.busy && b.mode == 0 && list.getSelectedIndex() >= 0);
        back.setVisible(b.inEvent()); back.setEnabled(true);
        rendering = false; revalidate(); repaint();
    }
}
