package forge.game.player;

import forge.game.GameActionUtil;
import forge.game.card.Card;
import forge.game.cost.Cost;
import forge.game.cost.CostBlight;
import forge.game.spellability.OptionalCost;
import forge.game.spellability.OptionalCostValue;
import forge.game.spellability.SpellAbility;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.testng.annotations.Test;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.testng.Assert.*;

public class OptionalCostSelectionTest {
    @Test
    public void nullSelectionDoesNotBecomeAFreeCast() {
        SpellAbility original = mock(SpellAbility.class);
        assertNull(GameActionUtil.addOptionalCosts(original, null));
        verify(original, never()).copy();
    }

    @Test
    public void explicitlyDecliningCostsPreservesTheOriginalAbility() {
        SpellAbility original = mock(SpellAbility.class);
        assertSame(GameActionUtil.addOptionalCosts(original, List.of()), original);
    }

    @Test
    public void selectedBlightIsAddedToACopy() {
        SpellAbility original = mock(SpellAbility.class);
        SpellAbility paid = mock(SpellAbility.class);
        when(original.copy()).thenReturn(paid);
        when(paid.getPayCosts()).thenReturn(new Cost("B", false));
        List<OptionalCostValue> costs = List.of(new OptionalCostValue(OptionalCost.Generic, new Cost("Blight<1>", false)));
        assertSame(GameActionUtil.addOptionalCosts(original, costs), paid);
        verify(paid).addOptionalCost(OptionalCost.Generic);
        assertTrue(paid.getPayCosts().getCostParts().stream().anyMatch(CostBlight.class::isInstance));
        verify(original, never()).addOptionalCost(any());
    }

    @Test
    public void failedRemoteChoiceAbortsBeforeChangingCardState() {
        Player player = mock(Player.class);
        PlayerController controller = mock(PlayerController.class);
        SpellAbility spell = mock(SpellAbility.class);
        Card card = mock(Card.class);
        when(player.getController()).thenReturn(controller);
        when(spell.getHostCard()).thenReturn(card);
        when(spell.isSpell()).thenReturn(true);
        when(controller.getAbilityToPlay(eq(card), anyList())).thenReturn(spell);
        List<OptionalCostValue> costs = List.of(new OptionalCostValue(OptionalCost.Generic, new Cost("Blight<1>", false)));
        when(controller.chooseOptionalCosts(spell, costs)).thenReturn(null);
        try (MockedStatic<GameActionUtil> actions = Mockito.mockStatic(GameActionUtil.class, CALLS_REAL_METHODS)) {
            actions.when(() -> GameActionUtil.getAdditionalCostSpell(spell)).thenReturn(List.of(spell));
            actions.when(() -> GameActionUtil.getOptionalCostValues(spell)).thenReturn(costs);
            assertFalse(PlaySpellAbility.playSpellAbility(controller, player, spell));
            verify(card, never()).setSplitStateToPlayAbility(any());
            verify(spell, never()).canPlay();
            verify(spell, never()).copy();
        }
    }
}
