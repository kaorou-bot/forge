package forge.deck.tournament;

import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Read-only Cardlist API. No credentials, scraping, or game-server state changes. */
public final class TournamentApi {
    public static final String BASE = "https://play.mtg-forge-kaorou.vip:8443/api/v1/";
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    public static final class Format {
        public final String code, name;
        public Format(String code, String name) { this.code = code; this.name = name; }
        @Override public String toString() { return name; }
    }
    public static final class Card {
        public final String name, chinese;
        public final int quantity;
        public Card(String name, String chinese, int quantity) {
            this.name = name; this.chinese = chinese; this.quantity = quantity;
        }
        @Override public String toString() {
            return quantity + " " + (chinese.isEmpty() ? name : chinese + " / " + name);
        }
    }
    public static final class DeckInfo {
        public String id, name, player, event, date, place, format, source;
        public String eventId = "", representativeName = "";
        public int eventDeckCount;
        public int mainCount, sideCount, commanderCount;
        public List<Card> main = new ArrayList<>(), side = new ArrayList<>(), commanders = new ArrayList<>();
        public boolean isCommander() { return "EDH".equals(format) || "cEDH".equals(format); }
        public String summary() {
            return name + " · " + player + " · #" + place + "\n" + date + " · " + event;
        }
    }
    public static final class Event {
        public final String id, name, date, format;
        public final int deckCount;
        Event(DeckInfo d) {
            id = d.eventId; name = d.event; date = d.date; format = d.format; deckCount = d.eventDeckCount;
        }
        public String key() { return format + ":" + id; }
        public String summary() {
            return name + "\n" + date + " · " + format + " · "
                    + (deckCount > 0 ? deckCount : "?") + " " + TournamentBrowser.text("deckCount", "decks");
        }
    }
    /** Merge across server pages by stable event ID, never by a possibly shared event name. */
    public static List<Event> mergeEvents(List<Event> previous, List<DeckInfo> decks) throws IOException {
        Map<String, Event> grouped = new LinkedHashMap<>();
        for (Event event : previous) grouped.put(event.key(), event);
        for (DeckInfo deck : decks) {
            if (deck.eventId.isEmpty()) throw new IOException("Missing event_id");
            Event event = new Event(deck);
            grouped.put(event.key(), event);
        }
        return new ArrayList<>(grouped.values());
    }
    public static final class Page {
        public final List<DeckInfo> decks;
        public final int page, pages, total;
        Page(List<DeckInfo> decks, int page, int pages, int total) {
            this.decks = decks; this.page = page; this.pages = pages; this.total = total;
        }
    }
    public static final class Usage {
        public String date, from, to;
        public boolean complete;
        public int sample;
        public List<UsageCard> cards = new ArrayList<>();
    }
    public static final class UsageCard {
        public final String name, label;
        UsageCard(String name, String label) { this.name = name; this.label = label; }
    }

    public List<Format> formats() throws IOException {
        List<Format> out = new ArrayList<>();
        for (JsonElement e : array(get("decks/formats"), "items")) {
            JsonObject o = e.getAsJsonObject();
            String code = required(o, "code");
            out.add(new Format(code, TournamentBrowser.text("format" + code, required(o, "name"))));
        }
        if (out.isEmpty()) throw new IOException("No formats available");
        return out;
    }
    public Page decks(String format, String query, int page) throws IOException {
        return decks(format, query, page, "");
    }
    private Page decks(String format, String query, int page, String date) throws IOException {
        JsonObject o = get("decks?format=" + encode(format) + "&page=" + page
                + "&page_size=100&q=" + encode(query)
                + (date.isEmpty() ? "" : "&date_from=" + encode(date) + "&date_to=" + encode(date)));
        List<DeckInfo> out = new ArrayList<>();
        for (JsonElement e : array(o, "items")) out.add(decodeDeck(e.getAsJsonObject(), false));
        return new Page(out, integer(o, "page"), integer(o, "total_pages"), integer(o, "total"));
    }
    public List<DeckInfo> eventDecks(Event event) throws IOException {
        Map<String, DeckInfo> all = new LinkedHashMap<>();
        int page = 1;
        Page response;
        do {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            response = decks(event.format, event.name, page, event.date);
            if (response.page != page || response.pages > 100)
                throw new IOException("Invalid or excessive event pagination");
            for (DeckInfo d : response.decks) {
                if (event.id.equals(d.eventId) && event.format.equals(d.format)) all.put(d.id, d);
            }
            page++;
        } while (page <= response.pages);
        List<DeckInfo> result = new ArrayList<>(all.values());
        if (event.deckCount > 0 && result.size() < event.deckCount)
            throw new IOException("Incomplete event deck list; please refresh the event list");
        result.sort(Comparator.comparingInt((DeckInfo d) -> placing(d.place)).thenComparing(d -> d.id));
        return result;
    }
    private static int placing(String place) {
        try { return Integer.parseInt(place.replace("#", "").split("-")[0].trim()); }
        catch (RuntimeException e) { return Integer.MAX_VALUE; }
    }
    public DeckInfo detail(String id) throws IOException {
        return decodeDeck(get("decks/" + encode(id)), true);
    }
    public Usage usage(String format, int days, boolean side) throws IOException {
        JsonObject o = get("decks/card-usage?format=" + encode(format) + "&window_days=" + days
                + "&board=" + (side ? "sideboard" : "mainboard"));
        return decodeUsage(o);
    }
    public static Usage decodeUsage(JsonObject o) throws IOException {
        Usage u = new Usage();
        u.date = required(o, "calculation_date");
        u.complete = o.has("sync_complete") && o.get("sync_complete").getAsBoolean();
        JsonArray groups = array(o, "groups");
        if (groups.isEmpty()) throw new IOException("No statistics available");
        JsonObject group = groups.get(0).getAsJsonObject();
        u.sample = integer(group, "total_decks");
        u.from = required(group, "window_start"); u.to = required(group, "window_end");
        for (JsonElement e : array(group, "items")) {
            JsonObject c = e.getAsJsonObject();
            String englishName = required(c, "name");
            String name = string(c, "name_zh");
            if (name.isEmpty()) name = englishName;
            double rate = c.get("usage_rate").getAsDouble();
            if (!Double.isFinite(rate) || rate < 0 || rate > 100) throw new IOException("Invalid usage rate");
            String delta = TournamentBrowser.text("noComparison", "No previous comparison");
            if (c.has("usage_rate_change_pp") && !c.get("usage_rate_change_pp").isJsonNull()) {
                double change = c.get("usage_rate_change_pp").getAsDouble();
                if (Double.isFinite(change)) delta = String.format(Locale.ROOT, "%+.2f pp", change);
            }
            u.cards.add(new UsageCard(englishName, integer(c, "rank") + ". " + name
                    + "  " + String.format(Locale.ROOT, "%.2f%%", rate)
                    + "\n" + integer(c, "deck_count") + " / " + u.sample + " · " + delta));
        }
        return u;
    }
    public static DeckInfo decodeDeck(JsonObject o, boolean full) throws IOException {
        DeckInfo d = new DeckInfo();
        d.id = required(o, "id"); d.name = required(o, "deck_name");
        d.player = string(o, "player"); d.event = string(o, "event_name");
        d.eventId = string(o, "event_id");
        d.eventDeckCount = o.has("event_deck_count") && !o.get("event_deck_count").isJsonNull()
                ? integer(o, "event_deck_count") : 0;
        if (o.has("representative_card") && o.get("representative_card").isJsonObject())
            d.representativeName = string(o.getAsJsonObject("representative_card"), "name");
        d.date = string(o, "event_date"); d.place = string(o, "place");
        d.format = required(o, "format_code"); d.source = string(o, "source_url");
        d.mainCount = integer(o, "mainboard_count"); d.sideCount = integer(o, "sideboard_count");
        d.commanderCount = o.has("commander_count") ? integer(o, "commander_count") : 0;
        if (full) {
            d.main = cards(array(o, "mainboard")); d.side = cards(array(o, "sideboard"));
            d.commanders = o.has("commanders") ? cards(array(o, "commanders")) : new ArrayList<>();
            if (count(d.main) != d.mainCount || count(d.side) != d.sideCount
                    || count(d.commanders) != d.commanderCount || d.mainCount == 0)
                throw new IOException("Incomplete deck list");
            if (d.isCommander() && d.commanders.isEmpty()) throw new IOException("Missing commander information");
        }
        return d;
    }
    private static List<Card> cards(JsonArray a) throws IOException {
        if (a.size() > 1000) throw new IOException("Deck too large");
        List<Card> cards = new ArrayList<>();
        for (JsonElement e : a) {
            JsonObject o = e.getAsJsonObject();
            int q = integer(o, "quantity");
            if (q < 1 || q > 1000) throw new IOException("Invalid card quantity");
            cards.add(new Card(required(o, "name"), string(o, "name_zh"), q));
        }
        if (count(cards) > 5000) throw new IOException("Deck too large");
        return cards;
    }
    private static int count(List<Card> cards) { return cards.stream().mapToInt(c -> c.quantity).sum(); }
    private static String encode(String s) throws IOException { return URLEncoder.encode(s, "UTF-8"); }
    private static JsonArray array(JsonObject o, String key) throws IOException {
        if (!o.has(key) || !o.get(key).isJsonArray()) throw new IOException("Missing " + key);
        return o.getAsJsonArray(key);
    }
    private static int integer(JsonObject o, String key) throws IOException {
        try {
            int n = o.get(key).getAsBigDecimal().intValueExact();
            if (n < 0) throw new ArithmeticException();
            return n;
        } catch (RuntimeException e) { throw new IOException("Invalid " + key, e); }
    }
    private static String required(JsonObject o, String key) throws IOException {
        String value = string(o, key);
        if (value.isEmpty()) throw new IOException("Missing " + key);
        return value;
    }
    private static String string(JsonObject o, String key) throws IOException {
        if (!o.has(key) || o.get(key).isJsonNull()) return "";
        String s = o.get(key).getAsString().replaceAll("[\\p{Cntrl}]", " ").trim();
        if (s.length() > 2000) throw new IOException("Field too long: " + key);
        return s;
    }
    private JsonObject get(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "Forge-Community-TournamentBrowser/1");
        try {
            int status = c.getResponseCode();
            if (status != 200) throw new IOException("HTTP " + status);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buf = new byte[8192]; int n;
                while ((n = in.read(buf)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                    if (bytes.size() + n > MAX_BYTES) throw new IOException("Response too large");
                    bytes.write(buf, 0, n);
                }
                return JsonParser.parseString(bytes.toString(StandardCharsets.UTF_8.name())).getAsJsonObject();
            } catch (RuntimeException e) { throw new IOException("Invalid server response", e); }
        } finally { c.disconnect(); }
    }
}
