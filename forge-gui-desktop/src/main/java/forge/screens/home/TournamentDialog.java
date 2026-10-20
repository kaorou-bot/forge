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
import forge.gui.util.SOptionPane;
import forge.toolbox.*;
import forge.view.FDialog;
import net.miginfocom.swing.MigLayout;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

/** Uses the active Forge skin rather than a separate web UI. */
public final class TournamentDialog extends FDialog implements TournamentBrowser.View {
    private final TournamentBrowser browser = new TournamentBrowser(this);
    private final FComboBox<TournamentApi.Format> format = new FComboBox<>();
    private final FComboBox<String> mode = new FComboBox<>(TournamentBrowser.modes());
    private final FList<String> list = new FList<>();
    private final FTextArea status = new FTextArea();
    private final FScrollPane listScroll = new FScrollPane(list, true);
    private final FButton search = new FButton(TournamentBrowser.text("search", "Search"));
    private final FButton refresh = new FButton(TournamentBrowser.text("refresh", "Refresh"));
    private final FButton next = new FButton(TournamentBrowser.text("moreEvents", "More events"));
    private final FButton open = new FButton(TournamentBrowser.text("open", "Open"));
    private final FButton back = new FButton(TournamentBrowser.text("backEvents", "Back to events"));
    private boolean rendering;
    private final Timer hold = new Timer(600, e -> showUsageCard());
    private final java.util.Map<PaperCard, CachedCardImage> thumbnails = new java.util.HashMap<>();
    private java.util.List<TournamentApi.Format> knownFormats;
    private java.util.List<String> knownRows;

    public static void showBrowser() {
        TournamentDialog dialog = new TournamentDialog();
        dialog.browser.start();
        dialog.setVisible(true);
    }
    private TournamentDialog() {
        super(false, true, "12");
        setTitle(TournamentBrowser.title());
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        getContentPane().setLayout(new MigLayout("insets 12, gap 8, fill, wrap 4", "[grow][grow][][]", "[][][grow][]"));
        add(format, "growx"); add(mode, "growx"); add(search); add(refresh);
        add(new FScrollPane(status, false), "span 4, growx, h 75!");
        add(listScroll, "span 4, grow, hidemode 3");
        add(next, "split 3, span 4"); add(open); add(back);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // Disable Swing's HTML interpretation of untrusted server strings.
        list.setCellRenderer((items, value, index, selected, focus) -> {
            FTextArea row = new FTextArea(value);
            row.setFont(FSkin.getFont(14));
            row.setOpaque(true);
            row.setBackground(FSkin.getColor(selected ? FSkin.Colors.CLR_ACTIVE : FSkin.Colors.CLR_THEME2));
            row.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
            JPanel panel = new JPanel(new BorderLayout(8, 0));
            panel.setOpaque(true); panel.setBackground(row.getBackground());
            if (browser.hasPictures()) {
                PaperCard card = browser.rowCard(index);
                JLabel picture = new JLabel("?", SwingConstants.CENTER);
                picture.setPreferredSize(new Dimension(browser.inEvent() ? 100 : 54, 76));
                if (card != null) {
                    CachedCardImage cached = thumbnails.computeIfAbsent(card, c ->
                            new CachedCardImage(CardView.getCardForUi(c), null, 240, 336) {
                                @Override public void onImageFetched() {
                                    FThreads.invokeInEdtLater(() -> { if (isDisplayable()) list.repaint(); });
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
            panel.add(row, BorderLayout.CENTER);
            return panel;
        });
        list.setFixedCellHeight(84);
        hold.setRepeats(false);
        format.addActionListener(e -> change()); mode.addActionListener(e -> change());
        refresh.addActionListener(e -> browser.refresh());
        next.addActionListener(e -> browser.more());
        open.addActionListener(e -> browser.select(list.getSelectedIndex()));
        list.addListSelectionListener(e -> open.setEnabled(!browser.busy
                && browser.mode == 0 && list.getSelectedIndex() >= 0));
        list.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (SwingUtilities.isLeftMouseButton(e) && e.getClickCount() == 2 && i >= 0
                        && list.getCellBounds(i, i).contains(e.getPoint())) {
                    if (browser.mode == 0) browser.select(list.getSelectedIndex());
                    else showUsageCard();
                }
            }
            @Override public void mousePressed(MouseEvent e) {
                int i = list.locationToIndex(e.getPoint());
                if (SwingUtilities.isLeftMouseButton(e) && i >= 0 && list.getCellBounds(i, i).contains(e.getPoint())) {
                    list.setSelectedIndex(i);
                    if (browser.mode != 0 && !browser.busy) hold.restart();
                }
            }
            @Override public void mouseReleased(MouseEvent e) { hold.stop(); }
            @Override public void mouseExited(MouseEvent e) { hold.stop(); }
        });
        list.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseDragged(MouseEvent e) { hold.stop(); }
        });
        back.addActionListener(e -> browser.back());
        search.addActionListener(e -> FThreads.invokeInBackgroundThread(() -> {
            String q = SOptionPane.showInputDialog(TournamentBrowser.text("searchPrompt", "Deck, player or event; empty clears the search"),
                    TournamentBrowser.title(), null, browser.query);
            if (q != null) FThreads.invokeInEdtLater(() -> browser.search(q));
        }));
        setMinimumSize(new Dimension(640, 440));
        setSize(900, 650); setLocationRelativeTo(getOwner());
    }
    @Override public void showDeck(Deck deck, Runnable save) {
        if (isDisplayable()) FDeckViewer.show(deck, save);
    }
    @Override public void message(String text) { FOptionPane.showMessageDialog(text); }
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
        if (browser.busy || !isDisplayable()) return;
        PaperCard card = browser.rowCard(list.getSelectedIndex());
        if (card == null) return;
        Deck oneCard = new Deck(card.getName());
        oneCard.getOrCreate(DeckSection.Main).add(card);
        // Native read-only viewer includes rules text, card image, and alternate-face controls.
        FDeckViewer.show(oneCard);
    }
    private void change() {
        if (!rendering) browser.filter(format.getSelectedIndex(), mode.getSelectedIndex());
    }
    @Override public void render(TournamentBrowser b) {
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
            knownRows = b.rows; thumbnails.clear();
            list.setListData(b.rows.toArray(new String[0]));
        }
        status.setText(b.status); status.setCaretPosition(0);
        next.setVisible(b.mode == 0 && !b.inEvent()); next.setEnabled(!b.busy && b.canLoadMore());
        open.setEnabled(!b.busy && b.mode == 0 && list.getSelectedIndex() >= 0);
        back.setVisible(b.inEvent()); back.setEnabled(true);
        rendering = false; getContentPane().revalidate(); repaint();
    }
    @Override public void dispose() { hold.stop(); thumbnails.clear(); browser.close(); super.dispose(); }
}
