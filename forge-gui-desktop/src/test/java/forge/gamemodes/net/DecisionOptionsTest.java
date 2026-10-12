package forge.gamemodes.net;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import org.apache.commons.lang3.tuple.Pair;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Collection contract only. Zone/controller legality requires a real Game fixture. */
public class DecisionOptionsTest {
    @Test
    public void listRechecksDeparturesWithoutChangingTheSourceOrItsOrder() {
        final Player survivor = mock(Player.class);
        final Player leaving = mock(Player.class);
        when(survivor.isInGame()).thenReturn(true);
        when(leaving.isInGame()).thenReturn(true);
        final List<Player> source = new ArrayList<>(List.of(survivor, leaving));
        Assert.assertEquals(DecisionOptions.list(source), source);
        when(leaving.isInGame()).thenReturn(false);
        Assert.assertEquals(DecisionOptions.list(source), List.of(survivor));
        Assert.assertEquals(source, List.of(survivor, leaving));
        Assert.assertEquals(new ArrayList<>(DecisionOptions.entities(source)), List.of(survivor));
    }

    @Test
    public void cardsOfDepartedOwnersAndTheirNestedPairsAreRemoved() {
        final Player survivor = mock(Player.class);
        final Player departed = mock(Player.class);
        when(survivor.isInGame()).thenReturn(true);
        final Card retained = mock(Card.class);
        final Card removed = mock(Card.class);
        final Game game = mock(Game.class);
        when(retained.getGame()).thenReturn(game);
        when(game.getCardState(retained, null)).thenReturn(retained);
        when(retained.equalsWithGameTimestamp(retained)).thenReturn(true);
        when(retained.getOwner()).thenReturn(survivor);
        when(removed.getOwner()).thenReturn(departed);
        Assert.assertEquals(new ArrayList<>(DecisionOptions.cards(List.of(removed, retained))), List.of(retained));
        final Pair<Object, Object> good = Pair.of("candidate", Pair.of(retained, survivor));
        final Pair<Object, Object> bad = Pair.of("candidate", Pair.of(retained, departed));
        Assert.assertEquals(DecisionOptions.list(List.of(bad, good)), List.of(good));
    }

    @Test
    public void absentOrReplacedCardObjectsAreNotOfferedAgain() {
        final Game game = mock(Game.class);
        final Player survivor = mock(Player.class);
        when(survivor.isInGame()).thenReturn(true);
        final Card absent = mock(Card.class);
        final Card old = mock(Card.class);
        final Card current = mock(Card.class);
        for (final Card card : List.of(absent, old, current)) {
            when(card.getGame()).thenReturn(game);
            when(card.getOwner()).thenReturn(survivor);
        }
        when(game.getCardState(old, null)).thenReturn(current);
        when(game.getCardState(current, null)).thenReturn(current);
        when(current.equalsWithGameTimestamp(current)).thenReturn(true);
        Assert.assertEquals(DecisionOptions.list(List.of(absent, old, current)), List.of(current));
    }

    @Test
    public void abilityCandidatesRespectTheirActualActivator() {
        final Player survivor = mock(Player.class);
        final Player departed = mock(Player.class);
        when(survivor.isInGame()).thenReturn(true);
        final SpellAbility retained = mock(SpellAbility.class);
        final SpellAbility removed = mock(SpellAbility.class);
        final SpellAbilityStackInstance instance = mock(SpellAbilityStackInstance.class);
        when(retained.getActivatingPlayer()).thenReturn(survivor);
        when(removed.getActivatingPlayer()).thenReturn(departed);
        when(instance.getActivatingPlayer()).thenReturn(departed);
        Assert.assertEquals(DecisionOptions.list(List.of(removed, instance, retained)), List.of(retained));
    }

    @Test
    public void mapPreservesValuesAndOrderAndDoesNotMutateTheOriginal() {
        final Player survivor = mock(Player.class);
        final Player departed = mock(Player.class);
        when(survivor.isInGame()).thenReturn(true);
        final Map<Object, Integer> source = new LinkedHashMap<>();
        source.put("first", 3);
        source.put(departed, 5);
        source.put(survivor, 7);
        final Map<Object, Integer> result = DecisionOptions.map(source);
        Assert.assertEquals(new ArrayList<>(result.keySet()), List.of("first", survivor));
        Assert.assertEquals(result.get(survivor), Integer.valueOf(7));
        Assert.assertEquals(source.size(), 3);
    }

    @Test
    public void nullContainersRemainDistinctFromEmptyContainers() {
        Assert.assertNull(DecisionOptions.list(null));
        Assert.assertNull(DecisionOptions.cards(null));
        Assert.assertNull(DecisionOptions.entities(null));
        Assert.assertNull(DecisionOptions.map(null));
        Assert.assertTrue(DecisionOptions.list(List.of()).isEmpty());
        Assert.assertTrue(DecisionOptions.map(Map.of()).isEmpty());
    }
}
