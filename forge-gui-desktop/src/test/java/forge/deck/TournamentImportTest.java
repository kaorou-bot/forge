package forge.deck;

import com.google.gson.JsonParser;
import forge.card.CardMockTestCase;
import forge.deck.tournament.TournamentApi;
import forge.deck.tournament.TournamentImporter;
import forge.deck.tournament.TournamentBrowser;
import forge.StaticData;
import org.testng.annotations.Test;
import java.io.IOException;
import java.util.*;
import static org.testng.Assert.*;

public class TournamentImportTest extends CardMockTestCase {
    private TournamentApi.DeckInfo source() {
        TournamentApi.DeckInfo d = new TournamentApi.DeckInfo();
        d.name = "Test"; d.source = ""; d.format = "ST";
        d.event = d.player = d.date = d.place = "";
        return d;
    }
    @Test public void preservesMainAndSideAndDoesNotDuplicateCommander() throws Exception {
        TournamentApi.DeckInfo d = source(); d.format = "cEDH";
        d.main = Arrays.asList(new TournamentApi.Card("Island", "", 99), new TournamentApi.Card("Lightning Bolt", "", 1));
        d.commanders = Collections.singletonList(new TournamentApi.Card("Lightning Bolt", "", 1));
        d.side = Collections.singletonList(new TournamentApi.Card("Shock", "", 2));
        Deck deck = TournamentImporter.prepare(d, name -> StaticData.instance().getCommonCards().getCard(name));
        assertEquals(deck.getMain().countAll(), 99);
        assertEquals(deck.get(DeckSection.Commander).countAll(), 1);
        assertEquals(deck.get(DeckSection.Sideboard).countAll(), 2);
    }
    @Test public void resolvesSplitNamesAndAccentsWithoutFuzzySubstitution() {
        assertEquals(TournamentImporter.key("Wear/Tear"), TournamentImporter.key("Wear // Tear"));
        assertEquals(TournamentImporter.key("Lorien Revealed"), TournamentImporter.key("Lórien Revealed"));
        assertNotEquals(TournamentImporter.key("Shock"), TournamentImporter.key("Lightning Bolt"));
    }
    @Test public void rejectsMissingCards() {
        TournamentApi.DeckInfo d = source();
        d.main = Collections.singletonList(new TournamentApi.Card("No such card", "", 4));
        expectThrows(IOException.class, () -> TournamentImporter.prepare(d, name -> null));
    }
    @Test public void validatesNames() throws Exception {
        assertEquals(TournamentImporter.validateName("  我的套牌  "), "我的套牌");
        for (String invalid : Arrays.asList("", "../bad", "a\\b", "CON", "foo.", "a\nb", "a:b")) {
            expectThrows(IOException.class, () -> TournamentImporter.validateName(invalid));
        }
    }
    @Test public void rejectsTruncatedOrInvalidPayloads() throws Exception {
        String base = "{\"id\":\"1\",\"deck_name\":\"deck\",\"format_code\":\"ST\","
                + "\"mainboard_count\":60,\"sideboard_count\":0,\"mainboard\":["
                + "{\"quantity\":60,\"name\":\"Island\"}],\"sideboard\":[]}";
        assertEquals(TournamentApi.decodeDeck(JsonParser.parseString(base).getAsJsonObject(), true).main.size(), 1);
        expectThrows(IOException.class, () -> TournamentApi.decodeDeck(JsonParser.parseString(
                base.replace("\"quantity\":60", "\"quantity\":59")).getAsJsonObject(), true));
        expectThrows(IOException.class, () -> TournamentApi.decodeDeck(JsonParser.parseString(
                base.replace("\"quantity\":60", "\"quantity\":-1")).getAsJsonObject(), true));
        expectThrows(IOException.class, () -> TournamentApi.decodeDeck(JsonParser.parseString(
                base.replace("\"ST\"", "\"cEDH\"")).getAsJsonObject(), true));
    }
    private String usagePayload() {
        return "{\"calculation_date\":\"2026-10-20\",\"sync_complete\":true,\"groups\":["
                + "{\"total_decks\":10,\"window_start\":\"2026-10-14\",\"window_end\":\"2026-10-20\","
                + "\"items\":[{\"name\":\"Lightning Bolt\",\"name_zh\":\"闪电击\","
                + "\"rank\":1,\"usage_rate\":50.0,\"deck_count\":5,\"usage_rate_change_pp\":null}]}]}";
    }
    @Test public void usageKeepsEnglishIdentitySeparateFromChineseLabel() throws Exception {
        TournamentApi.Usage usage = TournamentApi.decodeUsage(JsonParser.parseString(usagePayload()).getAsJsonObject());
        assertEquals(usage.cards.size(), 1);
        assertEquals(usage.cards.get(0).name, "Lightning Bolt");
        assertTrue(usage.cards.get(0).label.contains("闪电击"));
        assertTrue(usage.cards.get(0).label.contains("50.00%"));
        assertTrue(usage.cards.get(0).label.contains("5 / 10"));
        assertTrue(usage.complete);
    }
    @Test public void usageFallsBackToEnglishButRequiresIdentityAndValidRate() throws Exception {
        TournamentApi.Usage usage = TournamentApi.decodeUsage(JsonParser.parseString(
                usagePayload().replace("\"闪电击\"", "null")).getAsJsonObject());
        assertTrue(usage.cards.get(0).label.contains("Lightning Bolt"));
        expectThrows(IOException.class, () -> TournamentApi.decodeUsage(JsonParser.parseString(
                usagePayload().replace("\"Lightning Bolt\"", "\"\"")).getAsJsonObject()));
        expectThrows(IOException.class, () -> TournamentApi.decodeUsage(JsonParser.parseString(
                usagePayload().replace("\"usage_rate\":50.0", "\"usage_rate\":101")).getAsJsonObject()));
    }
    @Test public void preparingReadonlyPreviewDoesNotMutateServerDeck() throws Exception {
        TournamentApi.DeckInfo source = source();
        source.format = "EDH";
        source.main = Arrays.asList(new TournamentApi.Card("Island", "", 99), new TournamentApi.Card("Shock", "", 1));
        source.commanders = Collections.singletonList(new TournamentApi.Card("Shock", "", 1));
        Deck first = TournamentImporter.prepare(source, name -> StaticData.instance().getCommonCards().getCard(name));
        Deck second = TournamentImporter.prepare(source, name -> StaticData.instance().getCommonCards().getCard(name));
        assertNotSame(first, second);
        assertEquals(source.main.stream().mapToInt(c -> c.quantity).sum(), 100);
        assertEquals(first.getMain().countAll(), 99);
        assertEquals(second.getMain().countAll(), 99);
        assertEquals(second.get(DeckSection.Commander).countAll(), 1);
    }
    @Test public void groupsEventsByIdAcrossPagesNotBySharedName() throws Exception {
        TournamentApi.DeckInfo a = source(); a.eventId = "1"; a.event = "Weekly"; a.eventDeckCount = 8;
        TournamentApi.DeckInfo b = source(); b.eventId = "1"; b.event = "Weekly"; b.eventDeckCount = 8;
        TournamentApi.DeckInfo c = source(); c.eventId = "2"; c.event = "Weekly";
        List<TournamentApi.Event> first = TournamentApi.mergeEvents(Collections.emptyList(), Arrays.asList(a, b));
        assertEquals(first.size(), 1);
        assertEquals(first.get(0).deckCount, 8);
        List<TournamentApi.Event> second = TournamentApi.mergeEvents(first, Arrays.asList(b, c));
        assertEquals(second.size(), 2);
        assertEquals(second.get(0).id, "1");
        assertEquals(second.get(1).id, "2");
        expectThrows(IOException.class, () -> TournamentApi.mergeEvents(second, Collections.singletonList(source())));
    }
    @Test public void decodesServerRepresentativeCardWithoutGuessing() throws Exception {
        String json = "{\"id\":\"1\",\"deck_name\":\"deck\",\"format_code\":\"ST\","
                + "\"mainboard_count\":60,\"sideboard_count\":15,\"event_id\":\"42\",\"event_deck_count\":100,"
                + "\"representative_card\":{\"name\":\"Lightning Bolt\",\"name_zh\":\"闪电击\"}}";
        TournamentApi.DeckInfo d = TournamentApi.decodeDeck(JsonParser.parseString(json).getAsJsonObject(), false);
        assertEquals(d.eventId, "42");
        assertEquals(d.eventDeckCount, 100);
        assertEquals(d.representativeName, "Lightning Bolt");
        d = TournamentApi.decodeDeck(JsonParser.parseString(
                json.replace("{\"name\":\"Lightning Bolt\",\"name_zh\":\"闪电击\"}", "null")).getAsJsonObject(), false);
        assertEquals(d.representativeName, "");
    }
    @Test public void applicationDisposalClosesBackgroundBrowsersWithoutNetworkRequests() {
        int[] renders = {0};
        TournamentBrowser.View view = new TournamentBrowser.View() {
            @Override public void render(TournamentBrowser b) { renders[0]++; }
            @Override public void showDeck(Deck deck, Runnable save) { fail("Unexpected preview"); }
            @Override public void message(String text) { fail("Unexpected message"); }
        };
        TournamentBrowser first = new TournamentBrowser(view), second = new TournamentBrowser(view);
        try {
            TournamentBrowser.closeAll();
            first.start(); second.start();
            assertEquals(renders[0], 0, "Disposed browsers must not render or enqueue network work");
            first.close(); second.close(); TournamentBrowser.closeAll(); // idempotent
        } finally {
            first.close(); second.close();
        }
    }
}
