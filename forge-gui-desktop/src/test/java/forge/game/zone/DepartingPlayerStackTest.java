package forge.game.zone;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.spellability.SpellAbilityStackInstance;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.util.Deque;
import java.util.List;
import java.util.Stack;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Executes real MagicStack removal/unfreeze; reflective seeding avoids unrelated card casting. */
public class DepartingPlayerStackTest {
    @Test
    public void departureRemovesOnlyItsNormalFrozenAndSimultaneousEntries() throws Exception {
        final Fixture f = new Fixture();
        final SpellAbilityStackInstance departed = f.instance(f.departing, 1);
        final SpellAbilityStackInstance survivor = f.instance(f.surviving, 2);
        final SpellAbilityStackInstance frozenDeparted = f.instance(f.departing, 3);
        final SpellAbilityStackInstance frozenSurvivor = f.instance(f.surviving, 4);
        f.normal.add(departed);
        f.normal.add(survivor);
        f.frozen.add(frozenDeparted);
        f.frozen.add(frozenSurvivor);
        f.simultaneous.add(departed.getSpellAbility());
        f.simultaneous.add(survivor.getSpellAbility());
        f.stack.freezeStack(frozenDeparted.getSpellAbility());
        f.stack.removeInstancesControlledBy(f.departing);
        Assert.assertEquals(List.copyOf(f.normal), List.of(survivor));
        Assert.assertEquals(List.copyOf(f.frozen), List.of(frozenSurvivor));
        Assert.assertEquals(f.simultaneous, List.of(survivor.getSpellAbility()));
        Assert.assertNull(field(f.stack, "primaryAbility"));
        f.stack.unfreezeStack();
        Assert.assertTrue(f.frozen.isEmpty());
        Assert.assertEquals(List.copyOf(f.normal), List.of(frozenSurvivor, survivor));
        Assert.assertFalse(f.stack.isFrozen());
        f.stack.removeInstancesControlledBy(f.departing);
        Assert.assertEquals(f.stack.size(), 2, "Duplicate departure must preserve survivors");
    }

    @Test
    public void simultaneousWithoutActivatorUsesHostControllerAndPreservesOtherPrimary() throws Exception {
        final Fixture f = new Fixture();
        final SpellAbility departed = f.instance(f.departing, 1).getSpellAbility();
        final SpellAbility survivor = f.instance(f.surviving, 2).getSpellAbility();
        when(departed.getActivatingPlayer()).thenReturn(null);
        when(survivor.getActivatingPlayer()).thenReturn(null);
        f.simultaneous.add(departed);
        f.simultaneous.add(survivor);
        final SpellAbility primary = f.instance(f.surviving, 3).getSpellAbility();
        f.stack.freezeStack(primary);
        f.stack.removeInstancesControlledBy(f.departing);
        Assert.assertEquals(f.simultaneous, List.of(survivor));
        Assert.assertSame(field(f.stack, "primaryAbility"), primary);
    }

    private static Object field(final Object owner, final String name) throws Exception {
        final Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static final class Fixture {
        private final Game game = mock(Game.class, RETURNS_DEEP_STUBS);
        private final MagicStack stack = new MagicStack(game);
        private final Player departing = mock(Player.class);
        private final Player surviving = mock(Player.class);
        private final Deque<SpellAbilityStackInstance> normal;
        private final Stack<SpellAbilityStackInstance> frozen;
        private final List<SpellAbility> simultaneous;
        @SuppressWarnings("unchecked")
        private Fixture() throws Exception {
            when(game.getStack()).thenReturn(stack);
            normal = (Deque<SpellAbilityStackInstance>) field(stack, "stack");
            frozen = (Stack<SpellAbilityStackInstance>) field(stack, "frozenStack");
            simultaneous = (List<SpellAbility>) field(stack, "simultaneousStackEntryList");
        }
        private SpellAbilityStackInstance instance(final Player controller, final int id) {
            final Card source = mock(Card.class);
            when(source.getId()).thenReturn(id);
            when(source.getGame()).thenReturn(game);
            when(source.getController()).thenReturn(controller);
            final SpellAbility ability = mock(SpellAbility.class);
            when(ability.getId()).thenReturn(id);
            when(ability.getActivatingPlayer()).thenReturn(controller);
            when(ability.getHostCard()).thenReturn(source);
            when(ability.isCopied()).thenReturn(true);
            final SpellAbilityStackInstance instance = mock(SpellAbilityStackInstance.class);
            when(instance.getId()).thenReturn(id);
            when(instance.getActivatingPlayer()).thenReturn(controller);
            when(instance.getSpellAbility()).thenReturn(ability);
            when(instance.getSourceCard()).thenReturn(source);
            return instance;
        }
    }
}
