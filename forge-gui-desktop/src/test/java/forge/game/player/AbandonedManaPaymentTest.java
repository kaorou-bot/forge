package forge.game.player;

import forge.card.mana.ManaCost;
import forge.game.card.Card;
import forge.game.cost.CostPartMana;
import forge.game.spellability.SpellAbility;
import org.testng.Assert;
import org.testng.annotations.Test;

import static org.mockito.Mockito.*;

/** Component coverage of the real cleanup entry points, not a complete casting fixture. */
public class AbandonedManaPaymentTest {
    @Test
    public void exceptionalPaymentEntryReleasesBothReservationsWithoutGameActions() {
        final SpellAbility ability = mock(SpellAbility.class);
        final Card offering = mock(Card.class);
        final Card emerge = mock(Card.class);
        final IllegalStateException failure = new IllegalStateException("payment interrupted");
        when(ability.getHostCard()).thenThrow(failure);
        when(ability.getSacrificedAsOffering()).thenReturn(offering);
        when(ability.getSacrificedAsEmerge()).thenReturn(emerge);
        Assert.assertSame(Assert.expectThrows(IllegalStateException.class, () -> PlaySpellAbility.payManaCost(
                mock(PlayerController.class), ManaCost.ZERO, mock(CostPartMana.class), ability,
                mock(Player.class), "test payment", null, false)), failure);
        verify(offering).setUsedToPay(false);
        verify(emerge).setUsedToPay(false);
        verify(ability).resetSacrificedAsOffering();
        verify(ability).resetSacrificedAsEmerge();
        verifyNoMoreInteractions(offering, emerge);
    }

    @Test
    public void firstCleanupFailureStillReleasesTheOtherReservationAndReferences() {
        final SpellAbility ability = mock(SpellAbility.class);
        final Card offering = mock(Card.class);
        final Card emerge = mock(Card.class);
        final IllegalStateException failure = new IllegalStateException("offering flag cleanup");
        when(ability.getSacrificedAsOffering()).thenReturn(offering);
        when(ability.getSacrificedAsEmerge()).thenReturn(emerge);
        doThrow(failure).when(offering).setUsedToPay(false);
        Assert.assertSame(Assert.expectThrows(IllegalStateException.class,
                () -> PlaySpellAbility.clearAbandonedManaReservations(ability)), failure);
        verify(ability).resetSacrificedAsOffering();
        verify(emerge).setUsedToPay(false);
        verify(ability).resetSacrificedAsEmerge();
        verify(offering).setUsedToPay(false);
        verifyNoMoreInteractions(offering, emerge);
    }

    @Test
    public void clearingAbsentReservationsIsSafeToRepeat() {
        final SpellAbility ability = mock(SpellAbility.class);
        PlaySpellAbility.clearAbandonedManaReservations(ability);
        PlaySpellAbility.clearAbandonedManaReservations(ability);
        verify(ability, times(2)).resetSacrificedAsOffering();
        verify(ability, times(2)).resetSacrificedAsEmerge();
        verify(ability, times(2)).getSacrificedAsOffering();
        verify(ability, times(2)).getSacrificedAsEmerge();
        verifyNoMoreInteractions(ability);
    }
}
