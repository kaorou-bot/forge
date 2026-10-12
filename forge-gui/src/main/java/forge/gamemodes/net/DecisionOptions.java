package forge.gamemodes.net;

import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.util.collect.FCollection;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Revalidate cached candidate lists before asking a replacement decision maker. */
public final class DecisionOptions {
    private DecisionOptions() { }

    private static boolean available(final Object value) {
        if (value instanceof Player player) return player.isInGame();
        if (value instanceof Card card) {
            if (card.getOwner() != null && !card.getOwner().isInGame()) return false;
            final Card current = card.getGame().getCardState(card, null);
            return current != null && card.equalsWithGameTimestamp(current);
        }
        if (value instanceof SpellAbility ability) return ability.getActivatingPlayer() == null || ability.getActivatingPlayer().isInGame();
        if (value instanceof SpellAbilityStackInstance instance) return instance.getActivatingPlayer() == null || instance.getActivatingPlayer().isInGame();
        if (value instanceof Pair<?, ?> pair) return available(pair.getLeft()) && available(pair.getRight());
        return true;
    }

    public static <T> List<T> list(final Iterable<T> candidates) {
        if (candidates == null) return null;
        final List<T> result = new ArrayList<>();
        for (final T candidate : candidates) if (available(candidate)) result.add(candidate);
        return result;
    }

    public static CardCollection cards(final Iterable<Card> candidates) {
        return candidates == null ? null : new CardCollection(list(candidates));
    }

    public static <T> FCollection<T> entities(final Iterable<T> candidates) {
        return candidates == null ? null : new FCollection<>(list(candidates));
    }

    public static <K, V> Map<K, V> map(final Map<K, V> candidates) {
        if (candidates == null) return null;
        final Map<K, V> result = new LinkedHashMap<>();
        for (final var entry : candidates.entrySet()) if (available(entry.getKey())) result.put(entry.getKey(), entry.getValue());
        return result;
    }
}
