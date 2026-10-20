package forge.deck.tournament;

import forge.deck.*;
import forge.deck.io.DeckSerializer;
import forge.item.PaperCard;
import forge.model.FModel;
import forge.util.CardTranslation;
import forge.util.storage.IStorage;
import forge.localinstance.properties.ForgeConstants;
import java.io.*;
import java.nio.file.*;
import java.text.Normalizer;
import java.util.*;
import java.util.function.Function;

/** Strict import: missing/ambiguous cards never become substitute cards. */
public final class TournamentImporter {
    private TournamentImporter() { }

    public static String key(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .replace('\u2019', '\'').replace('\u2018', '\'')
                .replace('\u2013', '-').replace('\u2014', '-')
                .replaceAll("\\s*/+\\s*", " // ").trim().toLowerCase(Locale.ROOT);
    }
    public static Function<String, PaperCard> resolver() {
        Map<String, PaperCard> index = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (PaperCard c : FModel.getMagicDb().getCommonCards().getUniqueCards()) {
            List<String> aliases = new ArrayList<>(Arrays.asList(c.getName(), CardTranslation.getTranslatedName(c.getName())));
            if (c.getRules().getOtherPart() != null) {
                String front = c.getRules().getMainPart().getName(), back = c.getRules().getOtherPart().getName();
                aliases.add(front + " // " + back);
                aliases.add(front); aliases.add(back);
            }
            for (String alias : aliases) {
                String k = key(alias);
                PaperCard previous = index.putIfAbsent(k, c);
                if (previous != null && !previous.getName().equals(c.getName())) ambiguous.add(k);
            }
        }
        return name -> {
            PaperCard exact = FModel.getMagicDb().getCommonCards().getCard(name);
            if (exact != null) return exact;
            String k = key(name);
            return ambiguous.contains(k) ? null : index.get(k);
        };
    }
    public static Deck prepare(TournamentApi.DeckInfo source, Function<String, PaperCard> resolve) throws IOException {
        Deck deck = new Deck(source.name);
        deck.setSourceUrl(source.source);
        deck.setComment(source.event + " · " + source.date + " · " + source.player + " · " + source.place);
        Map<PaperCard, Integer> main = resolve(source.main, resolve);
        Map<PaperCard, Integer> side = resolve(source.side, resolve);
        Map<PaperCard, Integer> commanders = resolve(source.commanders, resolve);
        if (source.isCommander() && commanders.isEmpty()) throw new IOException("Missing commander");
        // Cardlist's commanders are also present in mainboard. Remove exactly their quantities.
        for (Map.Entry<PaperCard, Integer> c : commanders.entrySet()) {
            int remaining = main.getOrDefault(c.getKey(), 0) - c.getValue();
            if (remaining < 0) throw new IOException("Commander is not included in mainboard");
            main.put(c.getKey(), remaining);
            deck.getOrCreate(DeckSection.Commander).add(c.getKey(), c.getValue());
        }
        main.forEach((c, n) -> { if (n > 0) deck.getOrCreate(DeckSection.Main).add(c, n); });
        side.forEach((c, n) -> deck.getOrCreate(DeckSection.Sideboard).add(c, n));
        return deck;
    }
    private static Map<PaperCard, Integer> resolve(List<TournamentApi.Card> cards,
            Function<String, PaperCard> resolver) throws IOException {
        Map<PaperCard, Integer> out = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        for (TournamentApi.Card c : cards) {
            PaperCard p = resolver.apply(c.name);
            if (p == null) missing.add(c.name);
            else out.merge(p, c.quantity, Integer::sum);
        }
        if (!missing.isEmpty()) throw new IOException(TournamentBrowser.text("missing",
                "Forge does not support these cards; nothing was saved:") + "\n" + String.join("\n", missing));
        return out;
    }
    public static String validateName(String name) throws IOException {
        String n = name.trim();
        if (n.isEmpty() || n.length() > 100 || n.matches(".*[\\\\/:*?\"<>|\\p{Cntrl}].*")
                || n.endsWith(".") || n.matches("(?i)(con|prn|aux|nul|com[1-9]|lpt[1-9])(\\..*)?"))
            throw new IOException(TournamentBrowser.text("invalidName", "Enter a valid deck name (1–100 characters, no filename separators)."));
        return n;
    }
    /** Uses the normal Forge collection so imported decks appear without restarting. */
    public static Path save(Deck deck, String name, boolean commander) throws IOException {
        deck.setName(validateName(name));
        IStorage<Deck> storage = commander ? FModel.getDecks().getCommander() : FModel.getDecks().getConstructed();
        Path folder = Paths.get(commander ? ForgeConstants.DECK_COMMANDER_DIR : ForgeConstants.DECK_CONSTRUCTED_DIR);
        Files.createDirectories(folder);
        Path target = folder.resolve(deck.getBestFileName() + ".dck");
        synchronized (storage) {
            for (String existing : storage.getItemNames()) {
                if (existing.equalsIgnoreCase(deck.getName())) throw exists();
            }
            // Reserve with CREATE_NEW, including collisions with an unindexed on-disk deck.
            try { Files.createFile(target); } catch (FileAlreadyExistsException e) { throw exists(); }
            boolean success = false;
            try {
                storage.add(deck);
                Deck readBack = DeckSerializer.fromFile(target.toFile());
                if (readBack == null || !readBack.getName().equals(deck.getName())
                        || !readBack.getAllCardsInASinglePool().equals(deck.getAllCardsInASinglePool()))
                    throw new IOException("Could not verify saved deck");
                success = true;
                return target;
            } finally {
                if (!success) {
                    // Only the newly reserved name belongs to this operation.
                    storage.delete(deck.getName());
                    Files.deleteIfExists(target);
                }
            }
        }
    }
    private static IOException exists() {
        return new IOException(TournamentBrowser.text("nameExists", "This name already exists. Choose another name; existing decks are never overwritten."));
    }
}
