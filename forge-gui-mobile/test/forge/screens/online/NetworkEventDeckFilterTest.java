package forge.screens.online;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import forge.MobileTestEnvironment;
import forge.assets.FSkinFont;
import forge.deck.Deck;
import forge.deck.DeckProxy;
import forge.deck.DeckType;
import forge.deck.FDeckChooser;
import forge.gamemodes.match.GameLobby;
import forge.itemmanager.DeckManager;
import forge.screens.constructed.PlayerPanel;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.AfterClass;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class NetworkEventDeckFilterTest {
    private MockedStatic<FSkinFont> fonts;
    private MockedStatic<forge.assets.FSkin> skin;
    private MockedStatic<forge.model.FModel> model;
    private com.badlogic.gdx.Graphics originalGraphics;

    @BeforeClass
    public void initializeEnvironment() throws Exception {
        MobileTestEnvironment.initialize();
        forge.util.Localizer.getInstance().initialize("zh-CN", "../forge-gui/res/languages");
        originalGraphics = com.badlogic.gdx.Gdx.graphics;
        com.badlogic.gdx.Gdx.graphics = mock(com.badlogic.gdx.Graphics.class);
        when(com.badlogic.gdx.Gdx.graphics.getWidth()).thenReturn(1280);
        when(com.badlogic.gdx.Gdx.graphics.getHeight()).thenReturn(720);
        FSkinFont font = mock(FSkinFont.class);
        when(font.getLineHeight()).thenReturn(18f);
        fonts = mockStatic(FSkinFont.class);
        fonts.when(() -> FSkinFont.get(org.mockito.ArgumentMatchers.anyInt())).thenReturn(font);
        fonts.when(() -> FSkinFont._get(org.mockito.ArgumentMatchers.anyInt())).thenReturn(font);
        skin = mockStatic(forge.assets.FSkin.class);
        skin.when(forge.assets.FSkin::getImages).thenReturn(new java.util.HashMap<>());
        forge.StaticData database = mock(forge.StaticData.class);
        forge.card.CardEdition.Collection editions = mock(forge.card.CardEdition.Collection.class);
        when(editions.iterator()).thenAnswer(call -> java.util.Collections.emptyIterator());
        when(database.getEditions()).thenReturn(editions);
        model = mockStatic(forge.model.FModel.class, CALLS_REAL_METHODS);
        model.when(forge.model.FModel::getMagicDb).thenReturn(database);
        // Resolve enums outside Mockito stubbing; their initializers consult FModel.
        DeckType.values();
        forge.itemmanager.ItemManagerConfig.values();
    }

    @AfterClass(alwaysRun = true)
    public void releaseEnvironment() {
        if (model != null) model.close();
        if (skin != null) skin.close();
        if (fonts != null) fonts.close();
        com.badlogic.gdx.Gdx.graphics = originalGraphics;
    }

    @DataProvider(name = "rooms")
    public Object[][] rooms() {
        return new Object[][] {{2, false}, {2, true}, {8, false}, {8, true}};
    }

    private static final class Fixture {
        final GameLobby lobby = mock(GameLobby.class);
        final List<PlayerPanel> panels = new ArrayList<>();
        final FDeckChooser chooser = mock(FDeckChooser.class);
        final DeckManager decks = mock(DeckManager.class);
        final NetworkEventDeckFilter filter = new NetworkEventDeckFilter();

        Fixture(int slots, boolean initialized) {
            when(lobby.getNumberOfSlots()).thenReturn(slots);
            when(lobby.mayEdit(1)).thenReturn(true);
            for (int i = 0; i < 8; i++) panels.add(mock(PlayerPanel.class));
            when(panels.get(1).getDeckChooser()).thenReturn(chooser);
            when(chooser.isInitialized()).thenReturn(initialized);
            when(chooser.getLstDecks()).thenReturn(decks);
            when(chooser.getSelectedDeckType()).thenReturn(DeckType.CUSTOM_DECK);
        }

        void refresh(boolean limited, String event, boolean conformance, Supplier<List<DeckProxy>> pool) {
            filter.refresh(lobby, panels, limited, event, conformance, pool);
        }
    }

    @Test(dataProvider = "rooms")
    public void constructedPreservesTypeAndNeverLoadsPool(int count, boolean conformance) {
        Fixture f = new Fixture(count, true);
        f.refresh(false, "event-a", conformance, () -> { throw new AssertionError("Constructed loads event pool"); });
        verify(f.chooser, never()).setSelectedDeckType(any());
        verifyNoInteractions(f.decks);
        for (int i = 0; i < 8; i++) if (i != 1) verifyNoInteractions(f.panels.get(i));
    }

    @Test(dataProvider = "rooms")
    public void uninitializedAndRemoteSeatsWaitForLocalInitialization(int count, boolean conformance) {
        Fixture f = new Fixture(count, false);
        AtomicInteger loads = new AtomicInteger();
        Supplier<List<DeckProxy>> pool = () -> { loads.incrementAndGet(); return List.of(); };
        f.refresh(true, "event-a", conformance, pool);
        Assert.assertEquals(loads.get(), 0);
        verify(f.chooser, never()).getLstDecks();
        verify(f.chooser, never()).setSelectedDeckType(any());
        when(f.chooser.isInitialized()).thenReturn(true);
        f.refresh(true, "event-a", conformance, pool);
        Assert.assertEquals(loads.get(), 1);
        verify(f.chooser).setSelectedDeckType(DeckType.NET_EVENT_DECK);
        for (int i = 0; i < 8; i++) if (i != 1) verifyNoInteractions(f.panels.get(i));
    }

    @Test
    public void inactiveSeatOutsideLobbyIsNeverAccessed() {
        Fixture f = new Fixture(2, true);
        when(f.lobby.mayEdit(7)).thenReturn(true);
        f.refresh(true, null, false, List::of);
        verifyNoInteractions(f.panels.get(7));
        verify(f.lobby, never()).mayEdit(7);
    }

    private static DeckProxy deck(String name, String event) {
        Deck d = new Deck(name);
        if (event != null) d.getTags().add("eventId:" + event);
        return new DeckProxy(d, "Test", null, null);
    }

    @DataProvider(name = "filters")
    public Object[][] filters() {
        return new Object[][] {{"event-a", true, 1}, {"event-b", true, 1},
                {"missing", true, 0}, {"event-a", false, 4}, {null, true, 4}};
    }

    @Test(dataProvider = "filters")
    public void filtersCopyWithoutMutatingSharedPool(String event, boolean conformance, int expected) {
        Fixture f = new Fixture(8, true);
        DeckProxy a = deck("A", "event-a");
        DeckProxy b = deck("B", "event-b");
        List<DeckProxy> shared = List.of(a, b, deck("Untagged", null), mock(DeckProxy.class));
        f.refresh(true, event, conformance, () -> shared);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<DeckProxy>> capture = ArgumentCaptor.forClass(Iterable.class);
        verify(f.decks).setPool(capture.capture());
        List<DeckProxy> actual = new ArrayList<>();
        capture.getValue().forEach(actual::add);
        Assert.assertEquals(actual.size(), expected);
        if (expected == 1) Assert.assertSame(actual.get(0), "event-a".equals(event) ? a : b);
        Assert.assertEquals(shared.size(), 4);
        Assert.assertNotSame(capture.getValue(), shared);
    }

    @Test
    public void repeatedLimitedRefreshRestoresOriginalTypeAndName() {
        Fixture f = new Fixture(2, true);
        doReturn(deck("Original constructed", null)).when(f.decks).getSelectedItem();
        f.refresh(true, "event-a", true, () -> List.of(deck("Limited selection", "event-a")));
        when(f.chooser.getSelectedDeckType()).thenReturn(DeckType.NET_EVENT_DECK);
        doReturn(deck("Limited selection", "event-a")).when(f.decks).getSelectedItem();
        f.refresh(true, "event-b", false, List::of);
        clearInvocations(f.chooser, f.decks);
        f.refresh(false, null, false, () -> { throw new AssertionError("Restore loads pool"); });
        verify(f.chooser).setSelectedDeckType(DeckType.CUSTOM_DECK);
        verify(f.decks).setSelectedString("Original constructed");
        clearInvocations(f.chooser, f.decks);
        f.refresh(false, null, false, List::of);
        verify(f.chooser, never()).setSelectedDeckType(any());
        verifyNoInteractions(f.decks);
    }

    @Test
    public void nullOriginalSelectionRestoresTypeWithoutSelectingName() {
        Fixture f = new Fixture(2, true);
        f.refresh(true, null, false, List::of);
        clearInvocations(f.decks);
        f.refresh(false, null, false, List::of);
        verify(f.chooser).setSelectedDeckType(DeckType.CUSTOM_DECK);
        verify(f.decks, never()).setSelectedString(any());
    }

    @Test
    public void recursiveCallbackIsIgnoredAndNextRefreshStillRuns() {
        Fixture f = new Fixture(8, true);
        AtomicInteger loads = new AtomicInteger();
        f.refresh(true, null, false, () -> {
            loads.incrementAndGet();
            f.refresh(true, null, false, () -> { throw new AssertionError("Recursive pool load"); });
            return List.of();
        });
        f.refresh(true, null, false, () -> { loads.incrementAndGet(); return List.of(); });
        Assert.assertEquals(loads.get(), 2);
        verify(f.chooser, times(2)).setSelectedDeckType(DeckType.NET_EVENT_DECK);
    }

    @Test
    public void poolFailureReleasesGuardForRetry() {
        Fixture f = new Fixture(8, true);
        Assert.expectThrows(IllegalStateException.class, () -> f.refresh(true, null, false,
                () -> { throw new IllegalStateException("Injected pool failure"); }));
        f.refresh(true, null, false, List::of);
        verify(f.chooser).setSelectedDeckType(DeckType.NET_EVENT_DECK);
    }

    @Test
    public void chooserFailureReleasesGuardAndRetainsOriginalSelection() {
        Fixture f = new Fixture(8, true);
        doReturn(deck("Original", null)).when(f.decks).getSelectedItem();
        doThrow(new IllegalStateException("Injected chooser failure")).doNothing()
                .when(f.chooser).setSelectedDeckType(DeckType.NET_EVENT_DECK);
        Assert.expectThrows(IllegalStateException.class, () -> f.refresh(true, null, false, List::of));
        f.refresh(true, null, false, List::of);
        f.refresh(false, null, false, List::of);
        verify(f.chooser).setSelectedDeckType(DeckType.CUSTOM_DECK);
        verify(f.decks, atLeastOnce()).setSelectedString("Original");
    }

    @Test
    public void nullLobbyDoesNotAccessPanelsOrPool() {
        Fixture f = new Fixture(2, true);
        f.filter.refresh(null, f.panels, true, null, false,
                () -> { throw new AssertionError("No lobby loads pool"); });
        verifyNoInteractions(f.chooser, f.decks);
    }

    @Test
    public void multipleEditableSeatsShareOneLoadAndRestoreIndependentSelections() {
        Fixture f = new Fixture(8, true);
        FDeckChooser second = mock(FDeckChooser.class);
        DeckManager secondDecks = mock(DeckManager.class);
        when(f.lobby.mayEdit(6)).thenReturn(true);
        when(f.panels.get(6).getDeckChooser()).thenReturn(second);
        when(second.isInitialized()).thenReturn(true);
        when(second.getLstDecks()).thenReturn(secondDecks);
        when(second.getSelectedDeckType()).thenReturn(DeckType.PRECONSTRUCTED_DECK);
        doReturn(deck("Custom original", null)).when(f.decks).getSelectedItem();
        doReturn(deck("Precon original", null)).when(secondDecks).getSelectedItem();
        AtomicInteger loads = new AtomicInteger();
        f.refresh(true, null, false, () -> { loads.incrementAndGet(); return List.of(); });
        Assert.assertEquals(loads.get(), 1);
        verify(f.chooser).setSelectedDeckType(DeckType.NET_EVENT_DECK);
        verify(second).setSelectedDeckType(DeckType.NET_EVENT_DECK);
        clearInvocations(f.decks, secondDecks);
        f.refresh(false, null, false, () -> { throw new AssertionError("Restore loads pool"); });
        verify(f.chooser).setSelectedDeckType(DeckType.CUSTOM_DECK);
        verify(second).setSelectedDeckType(DeckType.PRECONSTRUCTED_DECK);
        verify(f.decks).setSelectedString("Custom original");
        verify(secondDecks).setSelectedString("Precon original");
    }
}
