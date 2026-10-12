package forge.screens.online;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import forge.deck.DeckProxy;
import forge.deck.DeckType;
import forge.deck.FDeckChooser;
import forge.gamemodes.match.GameLobby;
import forge.itemmanager.ItemManagerConfig;
import forge.screens.constructed.PlayerPanel;

/** Limited pools belong only to initialized seats editable on this device. */
final class NetworkEventDeckFilter {
    private record Selection(DeckType type, String name) { }
    private final Map<FDeckChooser, Selection> previous = new IdentityHashMap<>();
    private boolean updating;

    void refresh(final GameLobby lobby, final List<PlayerPanel> panels, final boolean limited,
                 final String eventId, final boolean conformance,
                 final Supplier<List<DeckProxy>> loadPool) {
        if (lobby == null || updating) return;
        updating = true;
        try {
            List<DeckProxy> pool = null;
            final int count = Math.min(lobby.getNumberOfSlots(), panels.size());
            for (int i = 0; i < count; i++) {
                if (!lobby.mayEdit(i)) continue;
                final FDeckChooser chooser = panels.get(i).getDeckChooser();
                // Remote seats deliberately never initialize their expensive selectors.
                // Local seats may still be in initialize() during recursive callbacks.
                if (!chooser.isInitialized()) continue;
                if (!limited) {
                    final Selection saved = previous.remove(chooser);
                    if (saved != null) {
                        chooser.setSelectedDeckType(saved.type());
                        if (saved.name() != null) chooser.getLstDecks().setSelectedString(saved.name());
                    }
                    continue;
                }
                if (pool == null) {
                    pool = new ArrayList<>(loadPool.get());
                    if (eventId != null && conformance) {
                        pool.removeIf(dp -> dp.getDeck() == null
                                || !eventId.equals(DeckProxy.getEventTag(dp.getDeck(), "eventId")));
                    }
                }
                final DeckProxy selected = chooser.getLstDecks().getSelectedItem();
                final String selectedName = selected == null ? null : selected.getName();
                previous.putIfAbsent(chooser, new Selection(chooser.getSelectedDeckType(), selectedName));
                chooser.setSelectedDeckType(DeckType.NET_EVENT_DECK);
                chooser.getLstDecks().setPool(pool);
                chooser.getLstDecks().setup(ItemManagerConfig.NET_EVENT_DECKS);
                if (selectedName != null) chooser.getLstDecks().setSelectedString(selectedName);
            }
        } finally {
            updating = false;
        }
    }
}
