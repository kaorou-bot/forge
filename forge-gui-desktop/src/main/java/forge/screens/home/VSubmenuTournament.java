package forge.screens.home;

import forge.Singletons;
import forge.deck.tournament.TournamentBrowser;
import forge.gui.framework.DragCell;
import forge.gui.framework.DragTab;
import forge.gui.framework.EDocID;
import forge.gui.framework.FScreen;
import forge.gui.framework.ICDoc;
import net.miginfocom.swing.MigLayout;

/** The community tournament browser is a first-level destination in Home. */
public enum VSubmenuTournament implements IVSubmenu<VSubmenuTournament.Controller> {
    SINGLETON_INSTANCE;

    private DragCell parentCell;
    private final DragTab tab = new DragTab(TournamentBrowser.title());
    private TournamentPanel panel;

    public static void showBrowser() {
        if (Singletons.getControl().setCurrentScreen(FScreen.HOME_SCREEN)) {
            CHomeUI.SINGLETON_INSTANCE.itemClick(EDocID.HOME_TOURNAMENT);
        }
    }

    @Override public EMenuGroup getGroupEnum() { return EMenuGroup.SETTINGS; }
    @Override public String getMenuTitle() { return TournamentBrowser.title(); }
    @Override public EDocID getItemEnum() { return EDocID.HOME_TOURNAMENT; }
    @Override public EDocID getDocumentID() { return EDocID.HOME_TOURNAMENT; }
    @Override public DragTab getTabLabel() { return tab; }
    @Override public void setParentCell(DragCell cell) { parentCell = cell; }
    @Override public DragCell getParentCell() { return parentCell; }
    @Override public Controller getLayoutControl() { return Controller.SINGLETON_INSTANCE; }

    @Override public void populate() {
        if (panel == null) panel = new TournamentPanel();
        VHomeUI.PnlDisplay display = VHomeUI.SINGLETON_INSTANCE.getPnlDisplay();
        display.removeAll();
        display.setLayout(new MigLayout("insets 0, gap 0, fill", "[grow,fill]", "[grow,fill]"));
        display.add(panel, "grow, push");
        display.revalidate();
        display.repaint();
    }

    public void deactivate() {
        if (panel != null) panel.deactivate();
    }

    public enum Controller implements ICDoc {
        SINGLETON_INSTANCE;
        @Override public void register() { }
        @Override public void initialize() { }
        @Override public void update() {
            TournamentPanel view = VSubmenuTournament.SINGLETON_INSTANCE.panel;
            if (view != null) view.activate();
        }
    }
}
