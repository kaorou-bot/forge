package forge.screens.home;

import forge.deck.Deck;
import forge.deck.tournament.TournamentApi;
import forge.deck.tournament.TournamentBrowser;
import forge.gui.GuiBase;
import forge.gui.interfaces.IGuiBase;
import forge.localinstance.skin.FSkinProp;
import forge.toolbox.FButton;
import forge.toolbox.FComboBox;
import forge.toolbox.FList;
import forge.toolbox.FScrollPane;
import forge.toolbox.FTextArea;
import forge.toolbox.FSkin;
import org.testng.annotations.AfterClass;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.swing.JPanel;
import javax.swing.ImageIcon;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.plaf.basic.BasicHTML;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

/** Exercises real Swing components without opening windows or contacting the public API. */
@Test(singleThreaded = true)
public class TournamentHomeTest {
    private final List<TournamentPanel> panels = new ArrayList<>();
    private BlockingQueue<Runnable> callbacks;
    private IGuiBase previousGui;
    private Map<FSkinProp, FSkin.SkinIcon> skinIcons;
    private Map<FSkinProp, FSkin.SkinIcon> previousIcons;

    @BeforeClass
    @SuppressWarnings("unchecked")
    public void initializeSkin() throws Exception {
        new forge.screens.match.layout.MatchSkinV4Test().initializeSkinPalette();
        Field icons = FSkin.SkinIcon.class.getDeclaredField("icons");
        icons.setAccessible(true);
        skinIcons = (Map<FSkinProp, FSkin.SkinIcon>) icons.get(null);
        previousIcons = new HashMap<>(skinIcons);
        var setIcon = FSkin.SkinIcon.class.getDeclaredMethod("setIcon", FSkinProp.class, ImageIcon.class);
        setIcon.setAccessible(true);
        ImageIcon placeholder = new ImageIcon(new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB));
        for (FSkinProp prop : FSkinProp.values()) {
            if (prop.name().startsWith("IMG_BTN_") && !skinIcons.containsKey(prop)) {
                setIcon.invoke(null, prop, placeholder);
            }
        }
    }

    @AfterClass(alwaysRun = true)
    public void restoreSkinIcons() {
        if (skinIcons != null && previousIcons != null) {
            skinIcons.clear();
            skinIcons.putAll(previousIcons);
        }
    }

    @BeforeMethod
    public void isolateCallbacks() {
        callbacks = new LinkedBlockingQueue<>();
        previousGui = GuiBase.getInterface();
        IGuiBase gui = mock(IGuiBase.class);
        when(gui.isGuiThread()).thenAnswer(inv -> SwingUtilities.isEventDispatchThread());
        when(gui.isRunningOnDesktop()).thenReturn(true);
        doAnswer(inv -> { callbacks.add(inv.getArgument(0)); return null; })
                .when(gui).invokeInEdtLater(any());
        doAnswer(inv -> { ((Runnable) inv.getArgument(0)).run(); return null; })
                .when(gui).invokeInEdtNow(any());
        GuiBase.setInterface(gui);
    }

    @AfterMethod(alwaysRun = true)
    public void disposeOnlyOwnedBrowsers() throws Exception {
        edt(() -> { for (TournamentPanel panel : panels) panel.deactivate(); });
        panels.clear();
        callbacks.clear();
        GuiBase.setInterface(previousGui);
    }

    @Test
    public void activationIsIdempotentAndReentryCreatesANewBrowser() throws Exception {
        edt(() -> {
            try (var construction = mockConstruction(TournamentBrowser.class)) {
                TournamentPanel panel = panel();
                panel.activate(); panel.activate();
                assertEquals(construction.constructed().size(), 1);
                TournamentBrowser first = construction.constructed().get(0);
                verify(first).start();
                Timer hold = field(panel, "hold");
                hold.start();
                panel.deactivate(); panel.deactivate();
                assertFalse(hold.isRunning());
                verify(first).close();
                assertNull(field(panel, "browser"));
                panel.activate();
                assertEquals(construction.constructed().size(), 2);
                verify(construction.constructed().get(1)).start();
                assertNotSame(first, field(panel, "browser"));
            }
        });
    }

    @Test
    public void serverMarkupIsRenderedAsPlainNoneditableText() throws Exception {
        edt(() -> {
            TournamentPanel panel = panel();
            TournamentBrowser b = browserMock(panel);
            String hostile = "<html><img src='https://invalid.example/image'>赛事 & player</html>\nsecond line";
            b.rows = List.of(hostile); b.status = hostile;
            panel.render(b);
            FList<String> list = field(panel, "list");
            JPanel renderer = (JPanel) list.getCellRenderer().getListCellRendererComponent(list, hostile, 0, true, false);
            FTextArea text = (FTextArea) ((BorderLayout) renderer.getLayout()).getLayoutComponent(BorderLayout.CENTER);
            assertEquals(text.getText(), hostile);
            assertNull(text.getClientProperty(BasicHTML.propertyKey));
            assertFalse(text.isEditable());
            assertEquals(((FTextArea) field(panel, "status")).getText(), hostile);
            assertEquals(list.getModel().getSize(), 1);
        });
    }

    @Test
    public void unknownServerFormatNamesCannotActivateHtml() throws Exception {
        edt(() -> {
            TournamentPanel panel = panel();
            TournamentBrowser b = browserMock(panel);
            String markup = "<html><b>Untrusted format</b></html>";
            b.formats = List.of(new TournamentApi.Format("unknown-format", markup));
            panel.render(b);
            FComboBox<TournamentApi.Format> combo = field(panel, "format");
            JComponent renderer = (JComponent) combo.getRenderer().getListCellRendererComponent(
                    new JList<>(), b.formats.get(0), 0, false, false);
            assertNull(renderer.getClientProperty(BasicHTML.propertyKey), "Server format labels must remain plain text");
            assertEquals(((javax.swing.JLabel) renderer).getText(), markup);
        });
    }

    @Test
    public void busyAndEmptySelectionCannotOpenRows() throws Exception {
        edt(() -> {
            TournamentPanel panel = panel();
            TournamentBrowser b = browserMock(panel);
            b.rows = List.of("first", "second"); b.busy = true;
            panel.render(b);
            FList<String> list = field(panel, "list");
            list.setSelectedIndex(1);
            list.getActionMap().get("open-row").actionPerformed(new ActionEvent(list, 0, "enter"));
            verify(b, never()).select(anyInt());
            assertFalse(((FButton) field(panel, "refresh")).isEnabled());
            assertFalse(((FButton) field(panel, "search")).isEnabled());
            b.busy = false; panel.render(b);
            assertTrue(((FButton) field(panel, "open")).isEnabled());
            ((FButton) field(panel, "open")).doClick(0);
            verify(b).select(1);
            list.clearSelection();
            assertFalse(((FButton) field(panel, "open")).isEnabled());
        });
    }

    @Test
    public void returnToEventsRestoresSelectionAndScroll() throws Exception {
        AtomicReference<TournamentPanel> view = new AtomicReference<>();
        edt(() -> {
            TournamentPanel panel = panel(); view.set(panel);
            TournamentBrowser b = browserMock(panel);
            AtomicBoolean inEvent = new AtomicBoolean();
            when(b.inEvent()).thenAnswer(inv -> inEvent.get());
            List<String> events = new ArrayList<>();
            for (int i = 0; i < 40; i++) events.add("Event " + i);
            b.rows = events; panel.render(b);
            FList<String> list = field(panel, "list");
            FScrollPane scroll = field(panel, "listScroll");
            list.setSelectedIndex(8);
            scroll.getVerticalScrollBar().setValues(360, 100, 0, 4000);
            ((FButton) field(panel, "open")).doClick(0);
            verify(b).select(8);
            inEvent.set(true); b.rows = List.of("Deck"); panel.render(b);
            inEvent.set(false); b.rows = events; b.rowChange = TournamentBrowser.RowChange.RETURN_TO_EVENTS;
            panel.render(b);
            assertEquals(list.getSelectedIndex(), 8);
            scroll.getVerticalScrollBar().setValues(0, 100, 0, 4000);
        });
        dispatchNext();
        edt(() -> assertEquals(((FScrollPane) field(view.get(), "listScroll")).getVerticalScrollBar().getValue(), 360));
    }

    @Test
    public void appendShowsOldAndNewRowsWithoutTriggeringRequests() throws Exception {
        AtomicReference<TournamentPanel> view = new AtomicReference<>();
        edt(() -> {
            TournamentPanel panel = panel(); view.set(panel);
            TournamentBrowser b = browserMock(panel);
            b.rows = List.of("first", "second"); panel.render(b);
            FList<String> list = field(panel, "list"); list.setSelectedIndex(1);
            FScrollPane scroll = field(panel, "listScroll");
            scroll.getVerticalScrollBar().setValues(60, 100, 0, 1000);
            b.rows = List.of("first", "second", "third"); b.rowChange = TournamentBrowser.RowChange.APPEND;
            panel.render(b);
            assertEquals(list.getModel().getSize(), 3);
            assertEquals(list.getModel().getElementAt(0), "first");
            assertEquals(list.getModel().getElementAt(2), "third");
            assertEquals(list.getSelectedIndex(), 1);
            verify(b, never()).filter(anyInt(), anyInt());
            verify(b, never()).select(anyInt());
        });
        dispatchNext();
        edt(() -> assertEquals(((FScrollPane) field(view.get(), "listScroll")).getVerticalScrollBar().getValue(), 60));
    }

    @Test
    public void obsoleteScrollCallbackCannotMoveNewRowsOrNewBrowser() throws Exception {
        AtomicReference<TournamentPanel> view = new AtomicReference<>();
        edt(() -> {
            TournamentPanel panel = panel(); view.set(panel);
            TournamentBrowser b = browserMock(panel);
            b.rows = List.of("first"); panel.render(b);
            FScrollPane scroll = field(panel, "listScroll");
            scroll.getVerticalScrollBar().setValues(60, 100, 0, 1000);
            b.rows = List.of("first", "second"); b.rowChange = TournamentBrowser.RowChange.APPEND;
            panel.render(b);
            b.rows = List.of("new query"); b.rowChange = TournamentBrowser.RowChange.RESET;
            panel.render(b);
            scroll.getVerticalScrollBar().setValues(20, 100, 0, 1000);
        });
        dispatchNext();
        edt(() -> {
            TournamentPanel panel = view.get();
            FScrollPane scroll = field(panel, "listScroll");
            assertEquals(scroll.getVerticalScrollBar().getValue(), 20);
            TournamentBrowser b = field(panel, "browser");
            b.rows = List.of("new query", "appended"); b.rowChange = TournamentBrowser.RowChange.APPEND;
            panel.render(b); panel.deactivate();
            TournamentBrowser replacement = browserMock(panel);
            replacement.rows = List.of("replacement"); panel.render(replacement);
            scroll.getVerticalScrollBar().setValues(40, 100, 0, 1000);
        });
        dispatchNext();
        edt(() -> assertEquals(((FScrollPane) field(view.get(), "listScroll")).getVerticalScrollBar().getValue(), 40));
    }

    @Test
    public void obsoleteBrowserCannotReplaceCurrentRows() throws Exception {
        edt(() -> {
            TournamentPanel panel = panel();
            TournamentBrowser old = browserMock(panel);
            old.rows = List.of("old"); panel.render(old); panel.deactivate();
            TournamentBrowser current = browserMock(panel);
            current.rows = List.of("new"); panel.render(current);
            old.rows = List.of("late old result"); panel.render(old);
            FList<String> list = field(panel, "list");
            assertEquals(list.getModel().getElementAt(0), "new");
        });
    }

    @Test(timeOut = 10000)
    public void queuedNetworkCompletionIsRejectedAfterLeavingAndReentering() throws Exception {
        AtomicReference<TournamentPanel> view = new AtomicReference<>();
        AtomicReference<TournamentBrowser> old = new AtomicReference<>();
        edt(() -> {
            TournamentPanel panel = panel(); view.set(panel);
            TournamentBrowser b = realBrowser(panel); old.set(b);
            TournamentApi api = mock(TournamentApi.class);
            when(api.decks(anyString(), anyString(), anyInt())).thenReturn(page());
            set(b, "api", api);
            b.refresh();
        });
        Runnable oldCompletion = callbacks.poll(3, TimeUnit.SECONDS);
        assertNotNull(oldCompletion, "A real browser request must have queued its completion");
        edt(() -> {
            view.get().deactivate();
            TournamentBrowser current = browserMock(view.get());
            current.rows = List.of("new page"); current.status = "current"; view.get().render(current);
            oldCompletion.run();
            assertEquals(((FList<?>) field(view.get(), "list")).getModel().getElementAt(0), "new page");
            assertEquals(((FTextArea) field(view.get(), "status")).getText(), "current");
            assertTrue(((ExecutorService) field(old.get(), "worker")).isShutdown());
        });
    }

    @Test(timeOut = 10000)
    public void leavingInterruptsTheOwnedPendingRequest() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        AtomicReference<TournamentPanel> view = new AtomicReference<>();
        edt(() -> {
            TournamentPanel panel = panel(); view.set(panel);
            TournamentBrowser b = realBrowser(panel);
            TournamentApi api = mock(TournamentApi.class);
            when(api.decks(anyString(), anyString(), anyInt())).thenAnswer(inv -> {
                entered.countDown();
                try { new CountDownLatch(1).await(5, TimeUnit.SECONDS); }
                catch (InterruptedException expected) { interrupted.countDown(); throw new IOException("cancelled", expected); }
                return page();
            });
            set(b, "api", api); b.refresh();
        });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        edt(() -> view.get().deactivate());
        assertTrue(interrupted.await(3, TimeUnit.SECONDS), "Leaving must cancel the real worker task");
        Runnable lateFailure = callbacks.poll(3, TimeUnit.SECONDS);
        assertNotNull(lateFailure);
        edt(() -> { lateFailure.run(); assertNull(field(view.get(), "browser")); });
    }

    @Test(timeOut = 10000)
    public void failedRefreshKeepsRowsAndCanRetry() throws Exception {
        AtomicReference<TournamentPanel> view = new AtomicReference<>();
        AtomicReference<TournamentBrowser> browser = new AtomicReference<>();
        edt(() -> {
            TournamentPanel panel = panel(); view.set(panel);
            TournamentBrowser b = realBrowser(panel); browser.set(b);
            b.rows = List.of("retained"); panel.render(b);
            TournamentApi api = mock(TournamentApi.class);
            when(api.decks(anyString(), anyString(), anyInt())).thenThrow(new IOException("offline")).thenReturn(page());
            set(b, "api", api); b.refresh();
        });
        dispatchNext();
        edt(() -> {
            assertFalse(browser.get().busy);
            assertEquals(((FList<?>) field(view.get(), "list")).getModel().getElementAt(0), "retained");
            assertTrue(((FTextArea) field(view.get(), "status")).getText().contains("offline"));
            FButton refresh = field(view.get(), "refresh"); assertTrue(refresh.isEnabled()); refresh.doClick(0);
        });
        dispatchNext();
        edt(() -> {
            assertFalse(browser.get().busy);
            assertEquals(((FList<?>) field(view.get(), "list")).getModel().getSize(), 0);
            assertFalse(((FTextArea) field(view.get(), "status")).getText().contains("offline"));
        });
    }

    @Test
    public void hiddenOrInactivePanelCannotOpenPreviewOrRunSaveAction() throws Exception {
        edt(() -> {
            TournamentPanel panel = spy(panel());
            browserMock(panel);
            Deck deck = new Deck("Read only"); Runnable save = mock(Runnable.class);
            doReturn(false).when(panel).isShowing();
            panel.showDeck(deck, save);
            panel.deactivate();
            doReturn(true).when(panel).isShowing();
            panel.showDeck(deck, save);
            verifyNoInteractions(save);
        });
    }

    @Test
    public void directMenuItemUsesDistinctIndentAndBoldSkinFont() throws Exception {
        edt(() -> {
            IVSubmenu<?> doc = mock(IVSubmenu.class);
            when(doc.getMenuTitle()).thenReturn("Tournament decks");
            LblMenuItem direct = new LblMenuItem(doc, true), nested = new LblMenuItem(doc);
            assertEquals(direct.getText(), "  Tournament decks");
            assertTrue(direct.getFont().isBold());
            assertFalse(nested.getFont().isBold());
            assertTrue(nested.getText().startsWith("      "));
        });
    }

    @Test
    public void longTitlesRetainPlainTextForAccessibility() throws Exception {
        edt(() -> {
            TournamentPanel panel = panel(); TournamentBrowser b = browserMock(panel);
            String title = "<html><img src='untrusted'><b>&amp; Long tournament title 长赛事名称</b></html>\n".repeat(20);
            b.rows = List.of(title); panel.render(b);
            FList<String> list = field(panel, "list");
            JPanel row = (JPanel) list.getCellRenderer().getListCellRendererComponent(list, title, 0, false, false);
            FTextArea text = (FTextArea) ((BorderLayout) row.getLayout()).getLayoutComponent(BorderLayout.CENTER);
            text.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 24)); text.setSize(240, 100);
            assertEquals(text.getText(), title);
            assertNull(text.getClientProperty(BasicHTML.propertyKey));
            list.setSize(480, 100);
            MouseEvent hover = new MouseEvent(list, MouseEvent.MOUSE_MOVED, 0, 0, 10, 10, 0, false);
            String tooltip = list.getToolTipText(hover);
            assertNotNull(tooltip);
            assertFalse(tooltip.contains("<img"));
            assertFalse(tooltip.contains("<b>"));
            assertTrue(tooltip.contains("&lt;img src='untrusted'&gt;"));
            assertTrue(tooltip.contains("&amp;amp;"));
            assertTrue(tooltip.contains("<br>"));
            assertEquals(tooltip.split("Long tournament title", -1).length - 1, 20);
            System.out.println("Tournament long-title metric: preferredTextHeight=" + text.getPreferredSize().height
                    + ", fixedRowHeight=" + list.getFixedCellHeight() + ", completeSafeTooltip=true");
        });
    }

    private TournamentPanel panel() {
        TournamentPanel panel = new TournamentPanel(); panels.add(panel); return panel;
    }

    private TournamentBrowser browserMock(TournamentPanel panel) {
        TournamentBrowser browser = mock(TournamentBrowser.class);
        browser.formats = List.of(new TournamentApi.Format("ST", "Standard"));
        browser.rows = List.of(); browser.status = ""; browser.query = "";
        set(panel, "browser", browser); return browser;
    }

    private TournamentBrowser realBrowser(TournamentPanel panel) {
        TournamentBrowser browser = new TournamentBrowser(panel);
        browser.formats = List.of(new TournamentApi.Format("ST", "Standard"));
        set(panel, "browser", browser); return browser;
    }

    private static TournamentApi.Page page() throws Exception {
        var constructor = TournamentApi.Page.class.getDeclaredConstructor(List.class, int.class, int.class, int.class);
        constructor.setAccessible(true); return constructor.newInstance(List.of(), 1, 1, 0);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name) {
        try { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return (T) f.get(owner); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static void set(Object owner, String name, Object value) {
        try { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); f.set(owner, value); }
        catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private void dispatchNext() throws Exception {
        Runnable callback = callbacks.poll(3, TimeUnit.SECONDS);
        assertNotNull(callback, "Expected a queued GUI callback"); edt(callback::run);
    }

    private interface Action { void run() throws Exception; }

    private static void edt(Action action) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { try { action.run(); } catch (Throwable t) { failure.set(t); } });
        if (failure.get() instanceof Error error) throw error;
        if (failure.get() instanceof Exception exception) throw exception;
    }
}
