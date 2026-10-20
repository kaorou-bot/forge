package forge.deck.tournament;

import forge.deck.Deck;
import forge.item.PaperCard;
import forge.gui.FThreads;
import forge.gui.util.SOptionPane;
import forge.util.Localizer;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

/** Events -> event decks -> native read-only viewer. No intermediate detail page. */
public final class TournamentBrowser implements AutoCloseable {
    private static final Set<TournamentBrowser> ACTIVE = ConcurrentHashMap.newKeySet();
    public interface View {
        void render(TournamentBrowser browser);
        void showDeck(Deck deck, Runnable save);
        void message(String text);
    }
    public static String text(String key, String fallback) {
        return Localizer.getInstance().getMessageorUseDefault("lblTournament_" + key, fallback);
    }
    public static String title() { return text("title", "Tournament decks"); }
    public static String[] modes() {
        return new String[] { text("events", "Latest events"), text("weekMain", "7 days · Main"),
                text("weekSide", "7 days · Sideboard"), text("monthMain", "30 days · Main"),
                text("monthSide", "30 days · Sideboard") };
    }
    private final View view;
    private final TournamentApi api = new TournamentApi();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Tournament browser"); t.setDaemon(true); return t;
    });
    private volatile boolean closed;
    private volatile long generation;
    private Future<?> pending;
    public enum RowChange { RESET, APPEND, RETURN_TO_EVENTS }
    public RowChange rowChange = RowChange.RESET;
    private Function<String, PaperCard> resolver;
    public List<TournamentApi.Format> formats = Collections.emptyList();
    public List<String> rows = Collections.emptyList();
    public String status = "", query = "";
    public int formatIndex, mode, page = 1, pages = 1;
    public boolean busy;
    private List<TournamentApi.Event> events = Collections.emptyList();
    private List<String> eventRows = Collections.emptyList();
    private List<TournamentApi.DeckInfo> decks = Collections.emptyList();
    private List<PaperCard> pictures = Collections.emptyList();
    private TournamentApi.Event event;

    public TournamentBrowser(View view) { this.view = view; ACTIVE.add(this); }
    /** An application can dispose while a child viewer, not the browser screen, is current. */
    public static void closeAll() {
        for (TournamentBrowser browser : new ArrayList<>(ACTIVE)) browser.close();
    }
    private Function<String, PaperCard> resolver() {
        if (resolver == null) resolver = TournamentImporter.resolver();
        return resolver;
    }
    public boolean inEvent() { return event != null; }
    public boolean canLoadMore() { return mode == 0 && !inEvent() && page < pages; }
    public boolean hasPictures() { return mode != 0 || inEvent(); }
    public PaperCard rowCard(int index) {
        return index >= 0 && index < pictures.size() ? pictures.get(index) : null;
    }
    public void start() {
        request(api::formats, result -> {
            formats = result;
            for (int i = 0; i < result.size(); i++) if ("ST".equals(result.get(i).code)) formatIndex = i;
            refresh();
        }, false);
    }
    public void filter(int format, int newMode) {
        if (busy || format < 0 || format >= formats.size()) return;
        formatIndex = format; mode = newMode;
        reset(); refresh();
    }
    public void search(String value) {
        if (busy) return;
        query = value.trim(); reset(); refresh();
    }
    private void reset() {
        rowChange = RowChange.RESET;
        event = null; page = pages = 1;
        rows = eventRows = Collections.emptyList();
        events = Collections.emptyList(); decks = Collections.emptyList(); pictures = Collections.emptyList();
    }
    public void more() { if (!busy && canLoadMore()) loadEvents(page + 1); }
    public void back() {
        if (!inEvent()) return;
        // Normal Android back can arrive while refreshing an event or fetching a deck.
        // Invalidate before cancelling so a queued UI callback cannot reopen the child.
        generation++;
        if (pending != null) pending.cancel(true);
        busy = false;
        event = null; decks = Collections.emptyList(); pictures = Collections.emptyList();
        rowChange = RowChange.RETURN_TO_EVENTS;
        rows = eventRows; eventStatus(); view.render(this);
    }
    private void eventStatus() {
        status = text("source", "MTGTop8 samples, not all tournament results") + "\n"
                + events.size() + " " + text("eventCount", "events") + " · " + page + " / " + pages
                + (query.isEmpty() ? "" : " · " + query)
                + (rows.isEmpty() ? " · " + text("empty", "No results") : "");
    }
    private void loadEvents(int requested) {
        List<TournamentApi.Event> previous = requested == 1 ? Collections.emptyList() : events;
        String code = formats.get(formatIndex).code;
        request(() -> {
            TournamentApi.Page p = api.decks(code, query, requested);
            if (p.page != requested) throw new java.io.IOException("Unexpected page");
            return new AbstractMap.SimpleImmutableEntry<>(p, TournamentApi.mergeEvents(previous, p.decks));
        }, result -> {
            events = result.getValue(); page = result.getKey().page; pages = Math.max(1, result.getKey().pages);
            List<String> labels = new ArrayList<>();
            for (TournamentApi.Event e : events) labels.add(e.summary());
            rowChange = requested > 1 ? RowChange.APPEND : RowChange.RESET;
            rows = eventRows = labels; pictures = Collections.emptyList();
            eventStatus(); view.render(this);
        }, false);
    }
    public void refresh() {
        if (busy || closed) return;
        if (formats.isEmpty()) { start(); return; }
        if (mode == 0) {
            if (inEvent()) openEvent(event);
            else loadEvents(1);
            return;
        }
        String code = formats.get(formatIndex).code;
        int days = mode <= 2 ? 7 : 30; boolean side = mode == 2 || mode == 4;
        request(() -> {
            TournamentApi.Usage u = api.usage(code, days, side);
            List<PaperCard> cards = new ArrayList<>();
            for (TournamentApi.UsageCard c : u.cards) cards.add(resolver().apply(c.name));
            return new AbstractMap.SimpleImmutableEntry<>(u, cards);
        }, result -> {
            TournamentApi.Usage u = result.getKey();
            pictures = result.getValue();
            List<String> labels = new ArrayList<>();
            for (TournamentApi.UsageCard c : u.cards) labels.add(c.label);
            rowChange = RowChange.RESET;
            rows = labels;
            status = text("statsNotice", "Usage in collected decks, not win rate. Change: percentage points (pp).")
                    + "\n" + u.from + " – " + u.to + " · " + text("sample", "Sample") + ": " + u.sample
                    + " · " + text("snapshot", "Snapshot") + ": " + u.date
                    + (u.complete ? "" : " · " + text("incomplete", "Sync incomplete"))
                    + "\n" + text("cardHint", "Hold a card to view details.");
            view.render(this);
        }, false);
    }
    public void select(int index) {
        if (busy || mode != 0 || index < 0) return;
        if (!inEvent()) {
            if (index < events.size()) openEvent(events.get(index));
        } else if (index < decks.size()) openDeck(decks.get(index));
    }
    private void openEvent(TournamentApi.Event selected) {
        request(() -> {
            List<TournamentApi.DeckInfo> result = api.eventDecks(selected);
            List<PaperCard> covers = new ArrayList<>();
            for (TournamentApi.DeckInfo d : result)
                covers.add(d.representativeName.isEmpty() ? null : resolver().apply(d.representativeName));
            return new AbstractMap.SimpleImmutableEntry<>(result, covers);
        }, result -> {
            event = selected; decks = result.getKey(); pictures = result.getValue();
            List<String> labels = new ArrayList<>();
            for (TournamentApi.DeckInfo d : decks) labels.add(d.name + "\n#" + d.place + " · " + d.player);
            rowChange = RowChange.RESET;
            rows = labels;
            status = selected.name + "\n" + selected.date + " · " + decks.size() + " " + text("deckCount", "decks")
                    + (decks.isEmpty() ? " · " + text("empty", "No results") : "");
            view.render(this);
        }, false);
    }
    private void openDeck(TournamentApi.DeckInfo selected) {
        String listStatus = status;
        request(() -> {
            TournamentApi.DeckInfo full = api.detail(selected.id);
            return new AbstractMap.SimpleImmutableEntry<>(full, TournamentImporter.prepare(full, resolver()));
        }, result -> {
            status = listStatus; view.render(this);
            view.showDeck(result.getValue(), () -> importDeck(result.getKey()));
        }, true);
    }
    private void importDeck(TournamentApi.DeckInfo source) {
        if (busy || closed) return;
        final long importTicket = generation + 1;
        request(() -> {
            Deck deck = TournamentImporter.prepare(source, resolver());
            if (closed || generation != importTicket) return null;
            String initial = (source.name + " - " + source.player).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").trim();
            if (initial.length() > 100) initial = initial.substring(0, 100);
            String name = SOptionPane.showInputDialog(text("namePrompt", "Edit the deck name before saving."),
                    text("saveTitle", "Import into custom decks"), SOptionPane.QUESTION_ICON, initial);
            if (name == null || closed || generation != importTicket) return null;
            TournamentImporter.validateName(name);
            return FThreads.callInEdtAndWaitChoice(() -> closed || generation != importTicket
                    ? null : TournamentImporter.save(deck, name, source.isCommander()));
        }, path -> {
            status = path == null ? text("cancelled", "Import cancelled")
                    : text("saved", "Saved to custom decks:") + "\n" + path;
            view.render(this);
            if (path != null) view.message(status);
        }, true);
    }
    private <T> void request(Callable<T> job, Consumer<T> success, boolean notifyFailure) {
        if (closed || busy) return;
        busy = true;
        long ticket = ++generation;
        status = text("loading", "Loading… You can leave this screen.");
        view.render(this);
        pending = worker.submit(() -> {
            try {
                T result = job.call();
                FThreads.invokeInEdtLater(() -> {
                    if (closed || ticket != generation) return;
                    busy = false; success.accept(result);
                });
            } catch (Exception e) {
                FThreads.invokeInEdtLater(() -> {
                    if (closed || ticket != generation) return;
                    busy = false;
                    Throwable reason = e;
                    while (reason.getCause() != null) reason = reason.getCause();
                    status = text("failed", "Operation failed; existing data was kept. Retry with Refresh.")
                            + "\n" + Objects.toString(reason.getMessage(), reason.getClass().getSimpleName());
                    view.render(this);
                    if (notifyFailure) view.message(status);
                });
            }
        });
    }
    @Override public void close() {
        if (closed) return;
        closed = true; generation++;
        if (pending != null) pending.cancel(true);
        worker.shutdownNow();
        ACTIVE.remove(this);
    }
}
